package kr.weathertalk.neighborhood.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import kr.weathertalk.member.MemberWithdrawn;

/**
 * 회원탈퇴함 구독 (02 §6, FR-MEM-09 표): 회원의 현재 동네·관심 동네를 지운다. 삭제라서 다시 전달돼도 결과가 같다(멱등).
 * 활성 동네는 조회 시점에 계산하므로 별도 집계 갱신이 필요 없다.
 */
@Component
class MemberWithdrawnListener {

	private final JdbcClient jdbc;

	MemberWithdrawnListener(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@ApplicationModuleListener
	void on(MemberWithdrawn event) {
		jdbc.sql("DELETE FROM nbr_current_neighborhood WHERE member_id = :m").param("m", event.memberId().value()).update();
		jdbc.sql("DELETE FROM nbr_interest_neighborhood WHERE member_id = :m").param("m", event.memberId().value()).update();
	}

}
