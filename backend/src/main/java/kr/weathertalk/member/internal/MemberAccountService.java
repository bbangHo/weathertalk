package kr.weathertalk.member.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import kr.weathertalk.member.MemberWithdrawn;
import kr.weathertalk.member.TemperatureSensitivity;
import kr.weathertalk.member.internal.auth.ProviderCredential;
import kr.weathertalk.member.internal.auth.ProviderCredentialCipher;
import kr.weathertalk.member.internal.auth.VerifiedProviderAccount;
import kr.weathertalk.member.internal.config.MemberProperties;
import kr.weathertalk.member.internal.domain.AuthProvider;
import kr.weathertalk.member.internal.domain.ExternalUnlinkJob;
import kr.weathertalk.member.internal.domain.LoginAccount;
import kr.weathertalk.member.internal.domain.LoginAccountRepository;
import kr.weathertalk.member.internal.domain.LoginSessionRepository;
import kr.weathertalk.member.internal.domain.Member;
import kr.weathertalk.member.internal.domain.MemberRepository;
import kr.weathertalk.member.internal.domain.Terms;
import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;

/**
 * 마이페이지 본인 기능: 프로필 조회(FR-MEM-10), 닉네임 변경(FR-MEM-11), 로그인 계정 연결·해제(FR-MEM-06·07), 탈퇴 로컬 확정(FR-MEM-09 규칙 1·2).
 */
@Service
public class MemberAccountService {

	/** 허용 문자: 한글·영문·숫자 (미결 01 §9 #1 확정 전 초기 규칙). */
	private static final Pattern NICKNAME_CHARS = Pattern.compile("[가-힣A-Za-z0-9]+");

	public record MyProfile(UUID memberId, String nickname, TemperatureSensitivity temperatureSensitivity,
			String email, List<AuthProvider> providers, Instant nicknameChangeAvailableAt, List<Terms> termsToReagree) {
	}

	public record NicknameChanged(String nickname, Instant nextChangeAvailableAt) {
	}

	private final MemberRepository members;
	private final LoginAccountRepository accounts;
	private final LoginSessionRepository sessions;
	private final TermsService termsService;
	private final AuthService authService;
	private final ExternalUnlinkService unlinks;
	private final ProviderCredentialCipher cipher;
	private final ApplicationEventPublisher events;
	private final MemberProperties properties;
	private final Clock clock;
	private final TransactionTemplate tx;

	MemberAccountService(MemberRepository members, LoginAccountRepository accounts, LoginSessionRepository sessions,
			TermsService termsService, AuthService authService, ExternalUnlinkService unlinks,
			ProviderCredentialCipher cipher, ApplicationEventPublisher events, MemberProperties properties, Clock clock,
			PlatformTransactionManager transactionManager) {
		this.members = members;
		this.accounts = accounts;
		this.sessions = sessions;
		this.termsService = termsService;
		this.authService = authService;
		this.unlinks = unlinks;
		this.cipher = cipher;
		this.events = events;
		this.properties = properties;
		this.clock = clock;
		this.tx = new TransactionTemplate(transactionManager);
	}

	/** 본인 프로필. 동네 정보를 포함하지 않으며 동네 유무와 무관하게 성공한다(legacy 01-#10). */
	@Transactional(readOnly = true)
	public MyProfile myProfile(UUID memberId) {
		Member member = activeMember(memberId);
		List<AuthProvider> providers = accounts.findByMemberId(memberId)
			.stream()
			.map(LoginAccount::provider)
			.sorted()
			.toList();
		return new MyProfile(member.id(), member.nickname(), member.temperatureSensitivity(), member.email(), providers,
				nextNicknameChange(member), termsService.termsRequiringReagreement(memberId));
	}

	/** 닉네임 변경 (FR-MEM-11, BR-MEM-15). */
	@Transactional
	public NicknameChanged changeNickname(UUID memberId, String requested) {
		String nickname = requested == null ? "" : requested.strip();
		int length = nickname.codePointCount(0, nickname.length());
		if (length < properties.nickname().minLength() || length > properties.nickname().maxLength()
				|| !NICKNAME_CHARS.matcher(nickname).matches()) {
			throw new DomainException(ErrorCode.INVALID_REQUEST, "닉네임은 " + properties.nickname().minLength() + "~"
					+ properties.nickname().maxLength() + "자의 한글·영문·숫자여야 합니다.");
		}
		Member member = members.findByIdForUpdate(memberId)
			.filter(Member::active)
			.orElseThrow(() -> new DomainException(ErrorCode.AUTHENTICATION_FAILED, "활동 중인 회원이 아닙니다."));
		if (nickname.equals(member.nickname())) {
			return new NicknameChanged(nickname, nextNicknameChange(member));
		}
		Instant now = clock.instant();
		Instant next = nextNicknameChange(member);
		if (next != null && now.isBefore(next)) {
			throw new DomainException(ErrorCode.NICKNAME_CHANGE_TOO_SOON, "닉네임은 아직 바꿀 수 없습니다.",
					Map.of("nextChangeAvailableAt", next.toString()));
		}
		if (members.existsByNickname(nickname)) {
			throw nicknameTaken();
		}
		member.changeNickname(nickname, now);
		try {
			members.saveAndFlush(member);
		}
		catch (DataIntegrityViolationException e) {
			// 사전 조회와 저장 사이에 다른 회원이 같은 닉네임을 가져간 경우(유일 제약이 최종 보장).
			throw nicknameTaken();
		}
		return new NicknameChanged(nickname, nextNicknameChange(member));
	}

	/**
	 * 로그인 계정 연결 (FR-MEM-06): 자격 검증(트랜잭션 밖) → 이미 그 제공자 계정이 있으면 거부 → 다른 회원에 연결된 계정이면 거부(병합 없음) → 연결.
	 */
	public MyProfile linkAccount(UUID memberId, ProviderCredential credential) {
		VerifiedProviderAccount verified = authService.verify(credential);
		String encrypted = cipher.encrypt(verified.unlinkCredential());
		try {
			tx.executeWithoutResult(status -> {
				lockActive(memberId);
				if (accounts.findByMemberId(memberId).stream().anyMatch(a -> a.provider() == verified.provider())) {
					throw new DomainException(ErrorCode.LOGIN_ACCOUNT_PROVIDER_ALREADY_LINKED,
							"이미 이 제공자의 로그인 계정이 연결돼 있습니다.");
				}
				if (accounts.existsByProviderAndProviderUserId(verified.provider(), verified.providerUserId())) {
					throw accountInUse();
				}
				accounts.saveAndFlush(LoginAccount.link(memberId, verified.provider(), verified.providerUserId(),
						encrypted, clock.instant()));
			});
		}
		catch (DataIntegrityViolationException e) {
			// 같은 계정을 다른 회원이 동시에 연결·가입한 경우(유일 제약이 최종 보장)
			throw accountInUse();
		}
		return myProfile(memberId);
	}

	/**
	 * 로그인 계정 해제 (FR-MEM-07): 마지막 계정이면 거부. 연결을 끊고 제공자 측 연결 해제를 탈퇴와 같은 방식(커밋 후 비동기·재시도)으로 한다.
	 */
	public MyProfile unlinkAccount(UUID memberId, AuthProvider provider) {
		tx.executeWithoutResult(status -> {
			lockActive(memberId);
			List<LoginAccount> mine = accounts.findByMemberId(memberId);
			LoginAccount target = mine.stream()
				.filter(a -> a.provider() == provider)
				.findFirst()
				.orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "연결된 로그인 계정이 없습니다."));
			if (mine.size() <= 1) {
				throw new DomainException(ErrorCode.LAST_LOGIN_ACCOUNT, "마지막 로그인 계정은 해제할 수 없습니다. 탈퇴를 이용해 주세요.");
			}
			unlinks.schedule(target, ExternalUnlinkJob.Reason.ACCOUNT_UNLINK);
			accounts.delete(target);
		});
		return myProfile(memberId);
	}

	/**
	 * 제공자 쪽에서 이미 끊긴 로그인 계정의 연결을 우리 쪽에서도 지운다(카카오 연결 해제 웹훅). 제공자가 이미 끊었으므로 연결 해제 작업은 남기지 않는다.
	 * 마지막 로그인 계정이어도 탈퇴시키지 않는다 — 회원 데이터는 남기고, 살아 있는 세션으로 다른 계정을 연결하거나 탈퇴할 수 있다.
	 * 모르는 계정·중복 알림이면 아무것도 하지 않는다.
	 *
	 * @return 지운 연결 수(0 또는 1)
	 */
	@Transactional
	public int forgetUnlinkedAccount(AuthProvider provider, String providerUserId) {
		return accounts.deleteByProviderAndProviderUserId(provider, providerUserId);
	}

	/**
	 * 탈퇴 로컬 확정 (FR-MEM-09 규칙 1). 한 트랜잭션에서 상태 탈퇴·개인정보 삭제 → 모든 세션 폐기 → 로그인 계정마다 제공자 연결 해제 작업 기록 →
	 * 로그인 계정 연결 제거 → {@link MemberWithdrawn} 발행(이벤트 발행 저장소에 같은 트랜잭션으로 영속). 외부 호출은 하지 않는다 —
	 * 제공자 연결 해제는 커밋 후 {@link ExternalUnlinkService}가 비동기로 한다(규칙 2·3, legacy 01-#3).
	 */
	@Transactional
	public void withdraw(UUID memberId) {
		Member member = members.findByIdForUpdate(memberId)
			.filter(Member::active)
			.orElseThrow(() -> new DomainException(ErrorCode.AUTHENTICATION_FAILED, "이미 탈퇴한 회원입니다."));
		Instant now = clock.instant();
		member.withdraw(now);
		members.saveAndFlush(member);
		sessions.revokeAllOf(memberId, now);
		for (LoginAccount account : accounts.findByMemberId(memberId)) {
			unlinks.schedule(account, ExternalUnlinkJob.Reason.WITHDRAWAL);
		}
		accounts.deleteByMemberId(memberId);
		events.publishEvent(new MemberWithdrawn(UUID.randomUUID(), member.memberId(), now));
	}

	private void lockActive(UUID memberId) {
		members.findByIdForUpdate(memberId)
			.filter(Member::active)
			.orElseThrow(() -> new DomainException(ErrorCode.AUTHENTICATION_FAILED, "활동 중인 회원이 아닙니다."));
	}

	private Instant nextNicknameChange(Member member) {
		return member.nicknameChangedAt() == null ? null
				: member.nicknameChangedAt().plus(properties.nickname().changeInterval());
	}

	private Member activeMember(UUID memberId) {
		return members.findById(memberId)
			.filter(Member::active)
			.orElseThrow(() -> new DomainException(ErrorCode.AUTHENTICATION_FAILED, "활동 중인 회원이 아닙니다."));
	}

	private static DomainException nicknameTaken() {
		return new DomainException(ErrorCode.NICKNAME_TAKEN, "이미 사용 중인 닉네임입니다.");
	}

	private static DomainException accountInUse() {
		return new DomainException(ErrorCode.LOGIN_ACCOUNT_IN_USE, "이미 다른 회원이 쓰는 로그인 계정입니다.");
	}

}
