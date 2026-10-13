package kr.weathertalk.neighborhood.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import kr.weathertalk.neighborhood.ForecastGrid;
import kr.weathertalk.neighborhood.internal.config.NeighborhoodProperties;
import kr.weathertalk.shared.Ids.NeighborhoodId;

/**
 * 활성 동네·활성 예보 격자 산출 (FR-NBR-09, BR-NBR-09).
 * <p>
 * 활성 동네 = 최근 활동 회원(현재 동네 확정 시각이 neighborhood.active-member-window 이내)의 현재 동네 ∪ 그 회원들의 관심 동네
 * ∪ 알림 구독 수가 1 이상인 동네(FR-NBR-10 — 비회원 기기만 구독한 동네도 사전 갱신·강수 시작 감시 대상이 된다).
 * 동네 수가 행정동 규모(수천)라 조회 시점에 계산한다. 비용이 커지면 사유 집계를 변경 시점에 갱신하는 방식으로 바꾼다(02 FR-NBR-09).
 */
@Component
class ActiveNeighborhoodQuery {

	private static final String ACTIVE_CODES = """
			WITH active_members AS (
			    SELECT member_id, neighborhood_code FROM nbr_current_neighborhood WHERE confirmed_at >= :since
			), reasons AS (
			    SELECT neighborhood_code FROM active_members
			    UNION
			    SELECT i.neighborhood_code FROM nbr_interest_neighborhood i JOIN active_members a ON a.member_id = i.member_id
			    UNION
			    SELECT neighborhood_code FROM nbr_notification_subscription WHERE subscription_count > 0
			)
			SELECT n.code, n.grid_nx, n.grid_ny FROM nbr_neighborhood n JOIN reasons r ON r.neighborhood_code = n.code
			WHERE n.status = 'VALID'
			""";

	private final JdbcClient jdbc;
	private final NeighborhoodProperties properties;
	private final Clock clock;

	ActiveNeighborhoodQuery(JdbcClient jdbc, NeighborhoodProperties properties, Clock clock) {
		this.jdbc = jdbc;
		this.properties = properties;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<NeighborhoodId> neighborhoods() {
		return jdbc.sql("SELECT code FROM (" + ACTIVE_CODES + ") active ORDER BY code")
			.param("since", since())
			.query((rs, n) -> new NeighborhoodId(rs.getString("code")))
			.list();
	}

	@Transactional(readOnly = true)
	public List<ForecastGrid> grids() {
		return jdbc.sql("SELECT DISTINCT grid_nx, grid_ny FROM (" + ACTIVE_CODES + ") active ORDER BY grid_nx, grid_ny")
			.param("since", since())
			.query((rs, n) -> new ForecastGrid(rs.getInt("grid_nx"), rs.getInt("grid_ny")))
			.list();
	}

	@Transactional(readOnly = true)
	public List<NeighborhoodId> neighborhoodsIn(ForecastGrid grid) {
		return jdbc
			.sql("SELECT code FROM (" + ACTIVE_CODES + ") active WHERE grid_nx = :nx AND grid_ny = :ny ORDER BY code")
			.param("since", since())
			.param("nx", grid.nx())
			.param("ny", grid.ny())
			.query((rs, n) -> new NeighborhoodId(rs.getString("code")))
			.list();
	}

	private Timestamp since() {
		return Timestamp.from(clock.instant().minus(properties.activeMemberWindow()));
	}

}
