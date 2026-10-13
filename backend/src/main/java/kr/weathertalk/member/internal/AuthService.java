package kr.weathertalk.member.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.weathertalk.member.MemberRegistered;
import kr.weathertalk.member.TemperatureSensitivity;
import kr.weathertalk.member.internal.LoginOutcome.MemberSummary;
import kr.weathertalk.member.internal.auth.IssuedTokens;
import kr.weathertalk.member.internal.auth.ProviderCredential;
import kr.weathertalk.member.internal.auth.ProviderCredentialVerifier;
import kr.weathertalk.member.internal.auth.TokenService;
import kr.weathertalk.member.internal.auth.VerifiedProviderAccount;
import kr.weathertalk.member.internal.config.MemberProperties;
import kr.weathertalk.member.internal.domain.AuthProvider;
import kr.weathertalk.member.internal.domain.LoginAccount;
import kr.weathertalk.member.internal.domain.LoginAccountRepository;
import kr.weathertalk.member.internal.domain.LoginSession;
import kr.weathertalk.member.internal.domain.LoginSessionRepository;
import kr.weathertalk.member.internal.domain.Member;
import kr.weathertalk.member.internal.domain.MemberRepository;
import kr.weathertalk.member.internal.domain.SignupTicket;
import kr.weathertalk.member.internal.domain.SignupTicketRepository;
import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;

/**
 * 로그인·가입 완료·토큰 재발급·로그아웃 (FR-MEM-02 흐름, FR-MEM-03·04·05).
 * <p>
 * 로그인은 제공자 검증 포트({@link ProviderCredentialVerifier})만 바꿔 끼우는 구조다. 지금은 DEV 검증기만 있고(개발 전용 로그인),
 * M7에서 실제 제공자 검증기를 추가해도 가입 티켓 → 가입 완료 → 토큰 흐름은 그대로다.
 */
@Service
public class AuthService {

	private static final SecureRandom RANDOM = new SecureRandom();

	private final Map<AuthProvider, ProviderCredentialVerifier> verifiers;
	private final MemberRepository members;
	private final LoginAccountRepository accounts;
	private final SignupTicketRepository tickets;
	private final LoginSessionRepository sessions;
	private final TermsService termsService;
	private final NicknameGenerator nicknameGenerator;
	private final TokenService tokenService;
	private final ApplicationEventPublisher events;
	private final MemberProperties properties;
	private final Clock clock;

	AuthService(List<ProviderCredentialVerifier> verifiers, MemberRepository members, LoginAccountRepository accounts,
			SignupTicketRepository tickets, LoginSessionRepository sessions, TermsService termsService,
			NicknameGenerator nicknameGenerator, TokenService tokenService, ApplicationEventPublisher events,
			MemberProperties properties, Clock clock) {
		this.verifiers = verifiers.stream()
			.collect(Collectors.toUnmodifiableMap(ProviderCredentialVerifier::provider, Function.identity()));
		this.members = members;
		this.accounts = accounts;
		this.tickets = tickets;
		this.sessions = sessions;
		this.termsService = termsService;
		this.nicknameGenerator = nicknameGenerator;
		this.tokenService = tokenService;
		this.events = events;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * 로그인 (FR-MEM-02 규칙 2·3). 연결된 활동 회원이 있으면 토큰, 없으면 가입 티켓을 준다.
	 */
	@Transactional
	public LoginOutcome login(ProviderCredential credential) {
		ProviderCredentialVerifier verifier = verifiers.get(credential.provider());
		if (verifier == null) {
			throw new DomainException(ErrorCode.INVALID_REQUEST, "지원하지 않는 로그인 제공자입니다.");
		}
		VerifiedProviderAccount account = verifier.verify(credential);
		Optional<Member> existing = accounts.findByProviderAndProviderUserId(account.provider(), account.providerUserId())
			.flatMap(a -> members.findById(a.memberId()))
			.filter(Member::active);
		if (existing.isPresent()) {
			return startSession(existing.get());
		}
		Instant now = clock.instant();
		String ticket = newOpaqueToken();
		Instant expiresAt = now.plus(properties.signupTicketTtl());
		tickets.save(SignupTicket.issue(hash(ticket), account.provider(), account.providerUserId(), account.email(),
				now, expiresAt));
		return new LoginOutcome.SignupRequired(ticket, expiresAt, termsService.currentTerms());
	}

	/**
	 * 가입 완료 (FR-MEM-03). 한 트랜잭션에서 회원 생성 → 무작위 닉네임 → 로그인 계정 연결 → 약관 동의 이력 →
	 * 세션·토큰 발급 → {@link MemberRegistered} 발행.
	 */
	@Transactional
	public LoginOutcome.Registered completeSignup(String signupTicket, List<TermsService.AgreementInput> agreements,
			TemperatureSensitivity sensitivity) {
		Instant now = clock.instant();
		SignupTicket ticket = Optional.ofNullable(signupTicket)
			.filter(t -> !t.isBlank())
			.flatMap(t -> tickets.findForUpdate(hash(t)))
			.filter(t -> !t.expired(now))
			.orElseThrow(AuthService::invalidTicket);

		// 같은 로그인 계정이 그사이(중복 탭·재시도) 가입을 마쳤다면 새 회원을 만들지 않고 그 회원으로 로그인한다(규칙 5).
		Optional<Member> already = accounts.findByProviderAndProviderUserId(ticket.provider(), ticket.providerUserId())
			.flatMap(a -> members.findById(a.memberId()))
			.filter(Member::active);
		if (already.isPresent()) {
			ticket.markUsed(now);
			return startSession(already.get());
		}
		if (ticket.used()) {
			throw invalidTicket();
		}
		if (sensitivity == null) {
			throw new DomainException(ErrorCode.INVALID_REQUEST, "온도 민감도를 선택해야 합니다.");
		}

		Member member = members.save(Member.register(uniqueNickname(), sensitivity, ticket.email(), now));
		termsService.recordSignupAgreements(member.id(), agreements);
		accounts.save(LoginAccount.link(member.id(), ticket.provider(), ticket.providerUserId(), now));
		ticket.markUsed(now);
		LoginOutcome.Registered registered = startSession(member);
		events.publishEvent(new MemberRegistered(UUID.randomUUID(), member.memberId(), now));
		return registered;
	}

	/**
	 * 토큰 재발급 (FR-MEM-04, BR-MEM-05). 세션 유효 + 세대 일치 + 회원 활동을 확인하고 매번 갱신 토큰을 회전한다.
	 * 회전된 이전 세대가 다시 오면 탈취로 보고 세션을 폐기한다 — 폐기는 오류 응답과 함께 커밋돼야 하므로 롤백하지 않는다.
	 */
	@Transactional(noRollbackFor = DomainException.class)
	public IssuedTokens refresh(String refreshToken) {
		TokenService.RefreshClaims claims = tokenService.verifyRefresh(refreshToken);
		Instant now = clock.instant();
		LoginSession session = sessions.findForUpdate(claims.sessionId())
			.filter(s -> s.memberId().equals(claims.memberId()))
			.orElseThrow(AuthService::authenticationFailed);
		if (!session.active()) {
			throw authenticationFailed();
		}
		Member member = members.findById(session.memberId()).orElse(null);
		if (member == null || !member.active()) {
			session.revoke(now);
			throw authenticationFailed();
		}
		if (claims.generation() == session.generation()) {
			session.rotate(now);
			return tokenService.issue(member.id(), session.id(), session.generation());
		}
		// 네트워크 재시도 등으로 직전 세대가 유예 시간 안에 다시 오면 같은 세대 토큰을 다시 준다(정상 회원을 로그아웃시키지 않음).
		boolean retryWithinGrace = claims.generation() == session.generation() - 1 && session.rotatedAt() != null
				&& now.isBefore(session.rotatedAt().plus(properties.refreshReuseGrace()));
		if (retryWithinGrace) {
			return tokenService.issue(member.id(), session.id(), session.generation());
		}
		session.revoke(now);
		throw new DomainException(ErrorCode.AUTHENTICATION_FAILED, "이미 사용된 갱신 토큰입니다. 다시 로그인해 주세요.");
	}

	/**
	 * 로그아웃 (FR-MEM-05): 로그인 세션을 폐기한다. 갱신 토큰이나 접근 토큰 중 하나로 세션을 찾는다(만료돼도 서명이 맞으면 허용).
	 * 이미 폐기된 세션이면 성공으로 본다(멱등).
	 */
	@Transactional
	public void logout(String accessToken, String refreshToken) {
		UUID sessionId;
		if (refreshToken != null && !refreshToken.isBlank()) {
			sessionId = tokenService.verifyRefreshIgnoringExpiry(refreshToken).sessionId();
		}
		else if (accessToken != null && !accessToken.isBlank()) {
			sessionId = tokenService.verifyAccessIgnoringExpiry(accessToken).sessionId();
		}
		else {
			throw new DomainException(ErrorCode.AUTHENTICATION_FAILED, "로그아웃할 토큰이 없습니다.");
		}
		Instant now = clock.instant();
		sessions.findForUpdate(sessionId).ifPresent(session -> session.revoke(now));
	}

	private LoginOutcome.Registered startSession(Member member) {
		LoginSession session = sessions.save(LoginSession.start(member.id(), clock.instant()));
		IssuedTokens tokens = tokenService.issue(member.id(), session.id(), session.generation());
		return new LoginOutcome.Registered(tokens, new MemberSummary(member.id(), member.nickname(),
				member.temperatureSensitivity(), termsService.termsRequiringReagreement(member.id())));
	}

	/** 충돌하면 다시 만든다. 중복 판별은 DB 제품 고유 오류 코드가 아니라 사전 조회로 한다(legacy 01-#9). */
	private String uniqueNickname() {
		for (int attempt = 0; attempt < properties.nickname().maxAttempts(); attempt++) {
			String candidate = nicknameGenerator.generate();
			if (!members.existsByNickname(candidate)) {
				return candidate;
			}
		}
		throw new DomainException(ErrorCode.TEMPORARILY_UNAVAILABLE, "닉네임을 정하지 못했습니다. 잠시 후 다시 시도해 주세요.");
	}

	private static String newOpaqueToken() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	static String hash(String token) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static DomainException invalidTicket() {
		return new DomainException(ErrorCode.SIGNUP_TICKET_INVALID, "가입 티켓이 만료되었거나 이미 사용되었습니다. 다시 로그인해 주세요.");
	}

	private static DomainException authenticationFailed() {
		return new DomainException(ErrorCode.AUTHENTICATION_FAILED, "유효하지 않은 갱신 토큰입니다.");
	}

}
