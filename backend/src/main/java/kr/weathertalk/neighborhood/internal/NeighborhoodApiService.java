package kr.weathertalk.neighborhood.internal;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.weathertalk.neighborhood.CurrentNeighborhood;
import kr.weathertalk.neighborhood.CurrentNeighborhoodConfirmation;
import kr.weathertalk.neighborhood.ForecastGrid;
import kr.weathertalk.neighborhood.InterestMergeResult;
import kr.weathertalk.neighborhood.Neighborhood;
import kr.weathertalk.neighborhood.NeighborhoodApi;
import kr.weathertalk.neighborhood.internal.config.NeighborhoodProperties;
import kr.weathertalk.shared.Ids.MemberId;
import kr.weathertalk.shared.Ids.NeighborhoodId;

/**
 * {@link NeighborhoodApi} 구현 (FR-NBR-01~10).
 */
@Service
class NeighborhoodApiService implements NeighborhoodApi {

	private final NeighborhoodRepository neighborhoods;
	private final CurrentNeighborhoodService currentService;
	private final InterestNeighborhoodService interestService;
	private final ActiveNeighborhoodQuery activeQuery;
	private final NotificationSubscriptionCounter subscriptionCounter;
	private final NeighborhoodProperties properties;

	NeighborhoodApiService(NeighborhoodRepository neighborhoods, CurrentNeighborhoodService currentService,
			InterestNeighborhoodService interestService, ActiveNeighborhoodQuery activeQuery,
			NotificationSubscriptionCounter subscriptionCounter,
			NeighborhoodProperties properties) {
		this.neighborhoods = neighborhoods;
		this.currentService = currentService;
		this.interestService = interestService;
		this.activeQuery = activeQuery;
		this.subscriptionCounter = subscriptionCounter;
		this.properties = properties;
	}

	@Override
	public CurrentNeighborhoodConfirmation confirmCurrentNeighborhood(MemberId memberId, NeighborhoodId neighborhoodId) {
		return currentService.confirm(memberId, neighborhoodId);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Neighborhood> findNeighborhood(NeighborhoodId id) {
		return neighborhoods.findById(id.value());
	}

	@Override
	@Transactional(readOnly = true)
	public Map<NeighborhoodId, Neighborhood> findNeighborhoods(Collection<NeighborhoodId> ids) {
		return neighborhoods.findByIds(ids.stream().map(NeighborhoodId::value).distinct().toList())
			.stream()
			.collect(Collectors.toMap(Neighborhood::id, Function.identity()));
	}

	/** 검색어가 neighborhood.search-min-length보다 짧으면 빈 결과(FR-NBR-07). */
	@Override
	@Transactional(readOnly = true)
	public List<Neighborhood> search(String query) {
		String q = query == null ? "" : query.strip();
		if (q.codePointCount(0, q.length()) < properties.searchMinLength()) {
			return List.of();
		}
		return neighborhoods.search(q, properties.searchMaxResults());
	}

	@Override
	public Optional<CurrentNeighborhood> findCurrentNeighborhood(MemberId memberId) {
		return currentService.find(memberId);
	}

	@Override
	public Map<MemberId, CurrentNeighborhood> findCurrentNeighborhoods(Collection<MemberId> memberIds) {
		return currentService.findAll(memberIds);
	}

	@Override
	public List<Neighborhood> findInterestNeighborhoods(MemberId memberId) {
		return interestService.list(memberId);
	}

	@Override
	public InterestMergeResult mergeInterestNeighborhoods(MemberId memberId, List<NeighborhoodId> neighborhoodIds) {
		return interestService.merge(memberId, neighborhoodIds);
	}

	@Override
	public void applyNotificationSubscriptionChange(NeighborhoodId neighborhoodId, int delta) {
		subscriptionCounter.apply(neighborhoodId, delta);
	}

	@Override
	public int reconcileNotificationSubscriptions(Map<NeighborhoodId, Integer> counts) {
		return subscriptionCounter.reconcile(counts);
	}

	@Override
	public List<NeighborhoodId> findActiveNeighborhoods() {
		return activeQuery.neighborhoods();
	}

	@Override
	public List<ForecastGrid> findActiveForecastGrids() {
		return activeQuery.grids();
	}

	@Override
	public List<NeighborhoodId> findActiveNeighborhoods(ForecastGrid grid) {
		return activeQuery.neighborhoodsIn(grid);
	}

}
