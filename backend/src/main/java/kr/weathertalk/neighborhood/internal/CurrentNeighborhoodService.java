package kr.weathertalk.neighborhood.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.weathertalk.neighborhood.CurrentNeighborhood;
import kr.weathertalk.neighborhood.CurrentNeighborhoodChanged;
import kr.weathertalk.neighborhood.CurrentNeighborhoodConfirmation;
import kr.weathertalk.neighborhood.DeviceLocation;
import kr.weathertalk.neighborhood.LocationRejection;
import kr.weathertalk.neighborhood.Neighborhood;
import kr.weathertalk.neighborhood.internal.config.NeighborhoodProperties;
import kr.weathertalk.shared.Ids.MemberId;
import kr.weathertalk.shared.Ids.NeighborhoodId;
import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;

/**
 * 좌표로 동네 판정(FR-NBR-01), 현재 동네 갱신(FR-NBR-02)·확정(FR-NBR-03)·조회(FR-NBR-04).
 * <p>
 * 좌표는 판정 쿼리의 파라미터로만 쓰고 버린다. 저장하는 것은 (회원 ID, 동네 ID, 확정 시각)뿐이며,
 * 예외 메시지·로그·이벤트에 좌표를 넣지 않는다(ADR 0009, BR-NBR-02).
 */
@Service
public class CurrentNeighborhoodService {

	/** 현재 동네 갱신 결과. refreshed=false면 최소 간격 안의 반복 요청이라 판정 없이 저장된 값을 돌려준 것. */
	public record RefreshResult(CurrentNeighborhood current, boolean refreshed) {
	}

	private final NeighborhoodRepository neighborhoods;
	private final JdbcClient jdbc;
	private final ApplicationEventPublisher events;
	private final NeighborhoodProperties properties;
	private final Clock clock;

	CurrentNeighborhoodService(NeighborhoodRepository neighborhoods, JdbcClient jdbc, ApplicationEventPublisher events,
			NeighborhoodProperties properties, Clock clock) {
		this.neighborhoods = neighborhoods;
		this.jdbc = jdbc;
		this.events = events;
		this.properties = properties;
		this.clock = clock;
	}

	/** FR-NBR-01: 순수 조회. 좌표 형식 오류는 INVALID_REQUEST, 어느 동네에도 없으면 빈 값. */
	@Transactional(readOnly = true)
	public Optional<Neighborhood> locate(DeviceLocation location) {
		LocationRejection rejection = validateCoordinates(location);
		if (rejection != null) {
			throw new DomainException(ErrorCode.INVALID_REQUEST, "좌표가 없거나 올바르지 않습니다.",
					Map.of("reason", rejection.name()));
		}
		return neighborhoods.locate(location.latitude(), location.longitude(), properties.maxNearestDistance());
	}

	/** FR-NBR-03: 쓰기 직전 확정. 정확도 기준 미달·판정 불가는 거부 사유로 돌려준다. */
	@Transactional
	public CurrentNeighborhoodConfirmation confirm(MemberId memberId, DeviceLocation location) {
		LocationRejection rejection = validateCoordinates(location);
		if (rejection == null) {
			rejection = validateAccuracyForWrite(location.accuracyMeters());
		}
		if (rejection != null) {
			return new CurrentNeighborhoodConfirmation.Rejected(rejection);
		}
		Optional<Neighborhood> located = neighborhoods.locate(location.latitude(), location.longitude(),
				properties.maxNearestDistance());
		if (located.isEmpty()) {
			return new CurrentNeighborhoodConfirmation.Rejected(LocationRejection.OUTSIDE_SERVICE_AREA);
		}
		save(memberId, located.get(), clock.instant());
		return new CurrentNeighborhoodConfirmation.Confirmed(located.get());
	}

	/**
	 * FR-NBR-02: 앱 진입·포그라운드 복귀 때 갱신. 직전 확정 후 neighborhood.current-refresh-min-interval 안이면
	 * 판정하지 않고 저장된 값을 그대로 돌려준다(앱의 생략 규칙을 서버도 지켜 잦은 호출을 흡수).
	 * 판정 불가면 저장된 현재 동네를 바꾸지 않고 NEIGHBORHOOD_NOT_RESOLVABLE.
	 */
	@Transactional
	public RefreshResult refresh(MemberId memberId, DeviceLocation location) {
		Instant now = clock.instant();
		Optional<CurrentNeighborhood> existing = find(memberId);
		if (existing.isPresent()
				&& now.isBefore(existing.get().confirmedAt().plus(properties.currentRefreshMinInterval()))) {
			return new RefreshResult(existing.get(), false);
		}
		Neighborhood located = locate(location).orElseThrow(() -> new DomainException(
				ErrorCode.NEIGHBORHOOD_NOT_RESOLVABLE, "이 위치가 속한 동네를 찾지 못했습니다."));
		save(memberId, located, now);
		return new RefreshResult(new CurrentNeighborhood(memberId, located, now), true);
	}

	@Transactional(readOnly = true)
	public Optional<CurrentNeighborhood> find(MemberId memberId) {
		return findAll(List.of(memberId)).values().stream().findFirst();
	}

	@Transactional(readOnly = true)
	public Map<MemberId, CurrentNeighborhood> findAll(Collection<MemberId> memberIds) {
		if (memberIds.isEmpty()) {
			return Map.of();
		}
		List<UUID> ids = memberIds.stream().map(MemberId::value).distinct().toList();
		record Row(UUID memberId, String code, Instant confirmedAt) {
		}
		List<Row> rows = jdbc
			.sql("SELECT member_id, neighborhood_code, confirmed_at FROM nbr_current_neighborhood WHERE member_id IN (:ids)")
			.param("ids", ids)
			.query((rs, n) -> new Row(rs.getObject("member_id", UUID.class), rs.getString("neighborhood_code"),
					rs.getTimestamp("confirmed_at").toInstant()))
			.list();
		Map<String, Neighborhood> byCode = neighborhoods.findByIds(rows.stream().map(Row::code).distinct().toList())
			.stream()
			.collect(Collectors.toMap(Neighborhood::administrativeCode, Function.identity()));
		return rows.stream()
			.collect(Collectors.toMap(r -> new MemberId(r.memberId()),
					r -> new CurrentNeighborhood(new MemberId(r.memberId()), byCode.get(r.code()), r.confirmedAt())));
	}

	/** 덮어쓰기(이력 없음). 동네가 바뀌었으면 현재동네변경됨 발행. */
	private void save(MemberId memberId, Neighborhood neighborhood, Instant now) {
		Optional<String> previous = jdbc
			.sql("SELECT neighborhood_code FROM nbr_current_neighborhood WHERE member_id = :id FOR UPDATE")
			.param("id", memberId.value())
			.query(String.class)
			.optional();
		jdbc.sql("INSERT INTO nbr_current_neighborhood (member_id, neighborhood_code, confirmed_at) VALUES (:id, :code, :at) "
				+ "ON CONFLICT (member_id) DO UPDATE SET neighborhood_code = EXCLUDED.neighborhood_code, "
				+ "confirmed_at = EXCLUDED.confirmed_at")
			.param("id", memberId.value())
			.param("code", neighborhood.administrativeCode())
			.param("at", Timestamp.from(now))
			.update();
		if (previous.isEmpty() || !previous.get().equals(neighborhood.administrativeCode())) {
			events.publishEvent(new CurrentNeighborhoodChanged(UUID.randomUUID(), memberId,
					previous.map(NeighborhoodId::new).orElse(null), neighborhood.id(), now));
		}
	}

	private static LocationRejection validateCoordinates(DeviceLocation location) {
		if (location == null || location.latitude() == null || location.longitude() == null) {
			return LocationRejection.LOCATION_MISSING;
		}
		double lat = location.latitude();
		double lon = location.longitude();
		if (!Double.isFinite(lat) || !Double.isFinite(lon) || lat < -90 || lat > 90 || lon < -180 || lon > 180) {
			return LocationRejection.LOCATION_INVALID;
		}
		Double accuracy = location.accuracyMeters();
		if (accuracy != null && (!Double.isFinite(accuracy) || accuracy < 0)) {
			return LocationRejection.LOCATION_INVALID;
		}
		return null;
	}

	private LocationRejection validateAccuracyForWrite(Double accuracyMeters) {
		if (accuracyMeters == null) {
			return LocationRejection.LOCATION_MISSING;
		}
		return accuracyMeters > properties.maxAccuracyForWrite() ? LocationRejection.ACCURACY_TOO_LOW : null;
	}

}
