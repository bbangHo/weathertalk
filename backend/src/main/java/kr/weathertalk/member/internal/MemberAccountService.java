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
import org.springframework.transaction.annotation.Transactional;

import kr.weathertalk.member.MemberWithdrawn;
import kr.weathertalk.member.TemperatureSensitivity;
import kr.weathertalk.member.internal.config.MemberProperties;
import kr.weathertalk.member.internal.domain.AuthProvider;
import kr.weathertalk.member.internal.domain.LoginAccount;
import kr.weathertalk.member.internal.domain.LoginAccountRepository;
import kr.weathertalk.member.internal.domain.LoginSessionRepository;
import kr.weathertalk.member.internal.domain.Member;
import kr.weathertalk.member.internal.domain.MemberRepository;
import kr.weathertalk.member.internal.domain.Terms;
import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;

/**
 * 마이페이지 본인 기능: 프로필 조회(FR-MEM-10), 닉네임 변경(FR-MEM-11), 탈퇴 로컬 확정(FR-MEM-09 규칙 1·2).
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
	private final ApplicationEventPublisher events;
	private final MemberProperties properties;
	private final Clock clock;

	MemberAccountService(MemberRepository members, LoginAccountRepository accounts, LoginSessionRepository sessions,
			TermsService termsService, ApplicationEventPublisher events, MemberProperties properties, Clock clock) {
		this.members = members;
		this.accounts = accounts;
		this.sessions = sessions;
		this.termsService = termsService;
		this.events = events;
		this.properties = properties;
		this.clock = clock;
	}

	/** 본인 프로필. 동네 정보를 포함하지 않으며 동네 유무와 무관하게 성공한다(legacy 01-#10). */
	@Transactional(readOnly = true)
	public MyProfile myProfile(UUID memberId) {
		Member member = activeMember(memberId);
		List<AuthProvider> providers = accounts.findByMemberId(memberId).stream().map(LoginAccount::provider).toList();
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
	 * 탈퇴 로컬 확정 (FR-MEM-09 규칙 1). 한 트랜잭션에서 상태 탈퇴·개인정보 삭제 → 모든 세션 폐기 → 로그인 계정 연결 제거 →
	 * {@link MemberWithdrawn} 발행(이벤트 발행 저장소에 같은 트랜잭션으로 영속). 외부 호출은 하지 않는다.
	 * 제공자 연결 해제 작업 기록·비동기 실행은 M7(소셜 로그인)에서 이 트랜잭션에 추가한다.
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
		accounts.deleteByMemberId(memberId);
		events.publishEvent(new MemberWithdrawn(UUID.randomUUID(), member.memberId(), now));
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

}
