package kr.weathertalk.member;

import kr.weathertalk.shared.Ids.MemberId;

/**
 * 회원 프로필 조회 결과 (contracts.md §2.1, FR-MEM-10). 탈퇴 회원은 닉네임 없이(null) 상태만 돌려준다 — 표시는 호출 모듈이 정한다.
 */
public record MemberProfile(MemberId memberId, String nickname, TemperatureSensitivity temperatureSensitivity,
		MemberStatus status) {

	public boolean withdrawn() {
		return status == MemberStatus.WITHDRAWN;
	}

}
