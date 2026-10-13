package kr.weathertalk.member.internal;

import java.time.Clock;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.weathertalk.member.MemberApi;
import kr.weathertalk.member.MemberProfile;
import kr.weathertalk.member.Requester;
import kr.weathertalk.member.SensitivityChangeSource;
import kr.weathertalk.member.TemperatureSensitivity;
import kr.weathertalk.member.TemperatureSensitivityChanged;
import kr.weathertalk.member.internal.auth.TokenService;
import kr.weathertalk.member.internal.domain.LoginSession;
import kr.weathertalk.member.internal.domain.LoginSessionRepository;
import kr.weathertalk.member.internal.domain.Member;
import kr.weathertalk.member.internal.domain.MemberRepository;
import kr.weathertalk.shared.Ids.MemberId;
import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;

/**
 * {@link MemberApi} 구현 (FR-MEM-01, 08, 10).
 * <p>
 * 현재 요청자 해석은 캐시 없이 요청마다 세션·회원 상태·역할·약관 동의를 DB에서 확인한다.
 * 탈퇴·로그아웃·역할 변경·재동의가 다음 요청부터 바로 반영된다(legacy 01-#20). 부하가 문제되면 무효화 가능한 캐시를 이 자리에 둔다.
 */
@Service
class MemberApiService implements MemberApi {

	private final TokenService tokenService;
	private final LoginSessionRepository sessions;
	private final MemberRepository members;
	private final TermsService termsService;
	private final ApplicationEventPublisher events;
	private final Clock clock;

	MemberApiService(TokenService tokenService, LoginSessionRepository sessions, MemberRepository members,
			TermsService termsService, ApplicationEventPublisher events, Clock clock) {
		this.tokenService = tokenService;
		this.sessions = sessions;
		this.members = members;
		this.termsService = termsService;
		this.events = events;
		this.clock = clock;
	}

	@Override
	@Transactional(readOnly = true)
	public Requester resolveRequester(String bearerToken) {
		if (bearerToken == null || bearerToken.isBlank()) {
			return Requester.GUEST;
		}
		TokenService.AccessClaims claims = tokenService.verifyAccess(bearerToken);
		boolean sessionActive = sessions.findById(claims.sessionId())
			.filter(LoginSession::active)
			.filter(s -> s.memberId().equals(claims.memberId()))
			.isPresent();
		Member member = members.findById(claims.memberId()).filter(Member::active).orElse(null);
		if (!sessionActive || member == null) {
			throw new DomainException(ErrorCode.AUTHENTICATION_FAILED, "로그아웃했거나 탈퇴한 회원의 토큰입니다.");
		}
		return new Requester.Member(member.memberId(), member.admin(), termsService.reagreementRequired(member.id()));
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<MemberProfile> findProfile(MemberId memberId) {
		return members.findById(memberId.value()).map(Member::toProfile);
	}

	@Override
	@Transactional(readOnly = true)
	public Map<MemberId, MemberProfile> findProfiles(Collection<MemberId> memberIds) {
		return members.findAllById(memberIds.stream().map(MemberId::value).collect(Collectors.toSet()))
			.stream()
			.map(Member::toProfile)
			.collect(Collectors.toMap(MemberProfile::memberId, Function.identity()));
	}

	@Override
	@Transactional
	public void changeTemperatureSensitivity(MemberId memberId, TemperatureSensitivity sensitivity,
			SensitivityChangeSource source) {
		if (sensitivity == null || source == null) {
			throw new DomainException(ErrorCode.INVALID_REQUEST, "온도 민감도와 변경 출처가 필요합니다.");
		}
		Member member = activeMemberForUpdate(memberId.value());
		TemperatureSensitivity previous = member.changeTemperatureSensitivity(sensitivity);
		if (previous != null) {
			events.publishEvent(new TemperatureSensitivityChanged(UUID.randomUUID(), memberId, previous, sensitivity,
					source, clock.instant()));
		}
	}

	private Member activeMemberForUpdate(UUID id) {
		return members.findByIdForUpdate(id)
			.filter(Member::active)
			.orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "활동 중인 회원이 아닙니다."));
	}

}
