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
import kr.weathertalk.neighborhood.LocationRejection;
import kr.weathertalk.neighborhood.Neighborhood;
import kr.weathertalk.neighborhood.NeighborhoodStatus;
import kr.weathertalk.shared.Ids.MemberId;
import kr.weathertalk.shared.Ids.NeighborhoodId;

/**
 * 현재 동네 확정(FR-NBR-03)·조회(FR-NBR-04).
 * <p>
 * 좌표는 받지 않는다. 앱이 기기 안에서 판정하고 사용자가 확인한 동네 ID만 받아, 존재하고 유효한지만 검사해 저장한다(ADR 0009).
 * 저장하는 것은 (회원 ID, 동네 ID, 확정 시각)뿐이다.
 */
@Service
public class CurrentNeighborhoodService {

	private final NeighborhoodRepository neighborhoods;
	private final JdbcClient jdbc;
	private final ApplicationEventPublisher events;
	private final Clock clock;

	CurrentNeighborhoodService(NeighborhoodRepository neighborhoods, JdbcClient jdbc, ApplicationEventPublisher events,
			Clock clock) {
		this.neighborhoods = neighborhoods;
		this.jdbc = jdbc;
		this.events = events;
		this.clock = clock;
	}

	/** FR-NBR-03: 동네 ID가 없거나·없는 동네거나·폐지된 동네면 거부 사유로 돌려준다. 확정되면 현재 동네로 저장한다. */
	@Transactional
	public CurrentNeighborhoodConfirmation confirm(MemberId memberId, NeighborhoodId neighborhoodId) {
		if (neighborhoodId == null || neighborhoodId.value() == null || neighborhoodId.value().isBlank()) {
			return new CurrentNeighborhoodConfirmation.Rejected(LocationRejection.NEIGHBORHOOD_MISSING);
		}
		Optional<Neighborhood> found = neighborhoods.findById(neighborhoodId.value());
		if (found.isEmpty()) {
			return new CurrentNeighborhoodConfirmation.Rejected(LocationRejection.NEIGHBORHOOD_UNKNOWN);
		}
		if (found.get().status() != NeighborhoodStatus.VALID) {
			return new CurrentNeighborhoodConfirmation.Rejected(LocationRejection.NEIGHBORHOOD_RETIRED);
		}
		save(memberId, found.get(), clock.instant());
		return new CurrentNeighborhoodConfirmation.Confirmed(found.get());
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

}
