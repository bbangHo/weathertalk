package kr.weathertalk.member;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

import kr.weathertalk.shared.Ids.MemberId;

/**
 * member 모듈의 공개 인터페이스 (contracts.md §2.1). 다른 모듈은 이 타입으로만 member 기능을 사용한다.
 */
public interface MemberApi {

	/**
	 * 현재 요청자 해석 (FR-MEM-01). 컨트롤러는 이 메서드 대신 {@link Requester} 파라미터를 선언한다.
	 * 캐시하지 않고 매번 세션·회원 상태·역할·약관 동의를 확인하므로 탈퇴·로그아웃·역할 변경·재동의가 즉시 반영된다.
	 *
	 * @param bearerToken 접근 토큰. 없으면(null·빈 값) 비회원이다.
	 * @return 회원(관리자 여부·약관 재동의 필요 여부 포함) 또는 {@link Requester#GUEST}
	 * @throws kr.weathertalk.shared.error.DomainException AUTHENTICATION_FAILED — 토큰이 잘못됐거나 만료·폐기됨, 갱신 토큰을 냄, 탈퇴 회원. 비회원으로 낮추지 않는다.
	 */
	Requester resolveRequester(String bearerToken);

	/** 회원 프로필 조회 (FR-MEM-10). 없는 회원 ID면 빈 값. */
	Optional<MemberProfile> findProfile(MemberId memberId);

	/** 회원 프로필 일괄 조회 (FR-MEM-10). 없는 회원 ID는 결과에서 빠진다. */
	Map<MemberId, MemberProfile> findProfiles(Collection<MemberId> memberIds);

	/**
	 * 온도 민감도 변경 (FR-MEM-08). 현재 값과 같으면 아무것도 하지 않는다(이벤트 없음).
	 * 다르면 바꾸고 {@link TemperatureSensitivityChanged}를 발행한다.
	 *
	 * @throws kr.weathertalk.shared.error.DomainException NOT_FOUND — 없는 회원 또는 탈퇴 회원
	 */
	void changeTemperatureSensitivity(MemberId memberId, TemperatureSensitivity sensitivity,
			SensitivityChangeSource source);

}
