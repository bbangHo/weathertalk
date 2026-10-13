package kr.weathertalk.neighborhood.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.weathertalk.neighborhood.InterestMergeResult;
import kr.weathertalk.neighborhood.InterestNeighborhoodsChanged;
import kr.weathertalk.neighborhood.Neighborhood;
import kr.weathertalk.neighborhood.NeighborhoodStatus;
import kr.weathertalk.neighborhood.internal.config.NeighborhoodProperties;
import kr.weathertalk.shared.Ids.MemberId;
import kr.weathertalk.shared.Ids.NeighborhoodId;
import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;

/**
 * 관심 동네 등록·해제·순서 변경(FR-NBR-05)·일괄 등록(FR-NBR-06). 회원당 최대 neighborhood.max-interest개, 중복 없음, 유효 동네만(BR-NBR-08).
 * <p>
 * 같은 회원의 동시 변경은 회원 단위 트랜잭션 잠금으로 줄 세워 최대 개수가 깨지지 않게 한다.
 */
@Service
public class InterestNeighborhoodService {

	private final NeighborhoodRepository neighborhoods;
	private final JdbcClient jdbc;
	private final ApplicationEventPublisher events;
	private final NeighborhoodProperties properties;
	private final Clock clock;

	InterestNeighborhoodService(NeighborhoodRepository neighborhoods, JdbcClient jdbc, ApplicationEventPublisher events,
			NeighborhoodProperties properties, Clock clock) {
		this.neighborhoods = neighborhoods;
		this.jdbc = jdbc;
		this.events = events;
		this.properties = properties;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<Neighborhood> list(MemberId memberId) {
		List<String> codes = codes(memberId);
		Map<String, Neighborhood> byCode = neighborhoods.findByIds(codes)
			.stream()
			.collect(Collectors.toMap(Neighborhood::administrativeCode, Function.identity()));
		return codes.stream().map(byCode::get).toList();
	}

	/** 등록. 이미 등록된 동네면 그대로 둔다(멱등). */
	@Transactional
	public List<Neighborhood> add(MemberId memberId, NeighborhoodId neighborhoodId) {
		lock(memberId);
		List<String> current = codes(memberId);
		if (!current.contains(neighborhoodId.value())) {
			requireValid(neighborhoodId);
			if (current.size() >= properties.maxInterest()) {
				throw new DomainException(ErrorCode.INTEREST_NEIGHBORHOOD_LIMIT_EXCEEDED,
						"관심 동네는 최대 " + properties.maxInterest() + "개까지 등록할 수 있습니다.",
						Map.of("maxInterest", properties.maxInterest()));
			}
			insert(memberId, neighborhoodId.value(), current.size() + 1, clock.instant());
			publish(memberId, List.of(neighborhoodId), List.of());
		}
		return list(memberId);
	}

	/** 해제. 없는 동네여도 성공(멱등). */
	@Transactional
	public List<Neighborhood> remove(MemberId memberId, NeighborhoodId neighborhoodId) {
		lock(memberId);
		int deleted = jdbc.sql("DELETE FROM nbr_interest_neighborhood WHERE member_id = :m AND neighborhood_code = :c")
			.param("m", memberId.value())
			.param("c", neighborhoodId.value())
			.update();
		if (deleted > 0) {
			renumber(memberId, codes(memberId));
			publish(memberId, List.of(), List.of(neighborhoodId));
		}
		return list(memberId);
	}

	/** 순서 변경: 지금 등록된 동네 전부를 원하는 순서로 보낸다. */
	@Transactional
	public List<Neighborhood> reorder(MemberId memberId, List<NeighborhoodId> order) {
		lock(memberId);
		List<String> requested = order.stream().map(NeighborhoodId::value).toList();
		if (new HashSet<>(requested).size() != requested.size()
				|| !new HashSet<>(requested).equals(new HashSet<>(codes(memberId)))) {
			throw new DomainException(ErrorCode.INVALID_REQUEST, "등록된 관심 동네 전부를 한 번씩 보내야 합니다.");
		}
		renumber(memberId, requested);
		return list(memberId);
	}

	/**
	 * 일괄 등록 (FR-NBR-06): 서버 목록 뒤에 순서대로 붙인다. 이미 있는·폐지된·없는 동네는 건너뛰고,
	 * 최대 개수를 넘는 나머지는 버린다. 반복 요청해도 최종 목록이 같다(멱등).
	 */
	@Transactional
	public InterestMergeResult merge(MemberId memberId, List<NeighborhoodId> deviceInterests) {
		lock(memberId);
		List<String> current = new ArrayList<>(codes(memberId));
		Set<String> requested = new LinkedHashSet<>(deviceInterests.stream().map(NeighborhoodId::value).toList());
		Map<String, Neighborhood> known = neighborhoods.findByIds(requested)
			.stream()
			.collect(Collectors.toMap(Neighborhood::administrativeCode, Function.identity()));
		List<NeighborhoodId> skipped = new ArrayList<>();
		List<NeighborhoodId> discarded = new ArrayList<>();
		List<NeighborhoodId> added = new ArrayList<>();
		Instant now = clock.instant();
		for (String code : requested) {
			Neighborhood neighborhood = known.get(code);
			if (current.contains(code) || neighborhood == null || neighborhood.status() != NeighborhoodStatus.VALID) {
				skipped.add(new NeighborhoodId(code));
			}
			else if (current.size() >= properties.maxInterest()) {
				discarded.add(new NeighborhoodId(code));
			}
			else {
				current.add(code);
				insert(memberId, code, current.size(), now);
				added.add(neighborhood.id());
			}
		}
		if (!added.isEmpty()) {
			publish(memberId, added, List.of());
		}
		return new InterestMergeResult(list(memberId), skipped, discarded);
	}

	private void requireValid(NeighborhoodId id) {
		boolean valid = neighborhoods.findById(id.value())
			.filter(n -> n.status() == NeighborhoodStatus.VALID)
			.isPresent();
		if (!valid) {
			throw new DomainException(ErrorCode.NOT_FOUND, "등록할 수 있는 동네가 아닙니다(없거나 폐지됨).");
		}
	}

	private List<String> codes(MemberId memberId) {
		return jdbc
			.sql("SELECT neighborhood_code FROM nbr_interest_neighborhood WHERE member_id = :m ORDER BY position, registered_at")
			.param("m", memberId.value())
			.query(String.class)
			.list();
	}

	private void insert(MemberId memberId, String code, int position, Instant now) {
		jdbc.sql("INSERT INTO nbr_interest_neighborhood (member_id, neighborhood_code, position, registered_at) "
				+ "VALUES (:m, :c, :p, :at)")
			.param("m", memberId.value())
			.param("c", code)
			.param("p", position)
			.param("at", Timestamp.from(now))
			.update();
	}

	private void renumber(MemberId memberId, List<String> ordered) {
		for (int i = 0; i < ordered.size(); i++) {
			jdbc.sql("UPDATE nbr_interest_neighborhood SET position = :p WHERE member_id = :m AND neighborhood_code = :c")
				.param("p", i + 1)
				.param("m", memberId.value())
				.param("c", ordered.get(i))
				.update();
		}
	}

	/** 회원 단위 트랜잭션 잠금(커밋·롤백 시 자동 해제). */
	private void lock(MemberId memberId) {
		jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
			.param("key", "nbr-interest:" + memberId.value())
			.query((rs, n) -> 1)
			.single();
	}

	private void publish(MemberId memberId, List<NeighborhoodId> added, List<NeighborhoodId> removed) {
		events.publishEvent(new InterestNeighborhoodsChanged(UUID.randomUUID(), memberId, added, removed,
				clock.instant()));
	}

}
