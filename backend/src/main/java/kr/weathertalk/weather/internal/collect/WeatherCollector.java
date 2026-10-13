package kr.weathertalk.weather.internal.collect;

import java.time.Clock;
import java.util.List;

import org.springframework.stereotype.Component;

import kr.weathertalk.neighborhood.ForecastGrid;
import kr.weathertalk.weather.internal.collect.CollectionCoordinator.Outcome;
import kr.weathertalk.weather.internal.source.AirKoreaSource;
import kr.weathertalk.weather.internal.source.KmaSource;
import kr.weathertalk.weather.internal.source.SourceData;
import kr.weathertalk.weather.internal.source.SourceException;
import kr.weathertalk.weather.internal.store.AirStore;
import kr.weathertalk.weather.internal.store.MidTermStore;
import kr.weathertalk.weather.internal.store.ObservationStore;
import kr.weathertalk.weather.internal.store.ShortTermStore;
import kr.weathertalk.weather.internal.store.UvStore;

/**
 * 데이터 종류별 수집 (FR-WEA-06·07·08). 모든 수집은 {@link CollectionCoordinator}의 한 경로를 지나므로
 * 요청 시 수집과 사전 갱신이 서로 중복되지 않고, 결과는 조회·판정이 함께 쓰는 하나의 사본이다.
 */
@Component
public class WeatherCollector {

	private final CollectionCoordinator coordinator;
	private final KmaSource kma;
	private final AirKoreaSource airKorea;
	private final ObservationStore observations;
	private final ShortTermStore shortTerms;
	private final MidTermStore midTerms;
	private final AirStore air;
	private final UvStore uv;
	private final Clock clock;

	public WeatherCollector(CollectionCoordinator coordinator, KmaSource kma, AirKoreaSource airKorea,
			ObservationStore observations, ShortTermStore shortTerms, MidTermStore midTerms, AirStore air, UvStore uv,
			Clock clock) {
		this.coordinator = coordinator;
		this.kma = kma;
		this.airKorea = airKorea;
		this.observations = observations;
		this.shortTerms = shortTerms;
		this.midTerms = midTerms;
		this.air = air;
		this.uv = uv;
		this.clock = clock;
	}

	public static String gridKey(ForecastGrid grid) {
		return grid.nx() + ":" + grid.ny();
	}

	public Outcome ensureObservation(ForecastGrid grid, boolean preRefresh) {
		return coordinator.ensure(CollectionKind.OBSERVATION, gridKey(grid), () -> observations.latestObservedAt(grid),
				issuedAt -> observations.save(grid, kma.fetchObservation(grid, issuedAt), clock.instant()),
				preRefresh);
	}

	public Outcome ensureShortTerm(ForecastGrid grid, boolean preRefresh) {
		return coordinator.ensure(CollectionKind.SHORT_TERM, gridKey(grid), () -> shortTerms.latestIssuedAt(grid),
				issuedAt -> shortTerms.replace(grid, issuedAt, kma.fetchShortTerm(grid, issuedAt), clock.instant()),
				preRefresh);
	}

	public Outcome ensureMidLand(String regionId, boolean preRefresh) {
		return coordinator.ensure(CollectionKind.MID_LAND, regionId, () -> midTerms.latestLandIssuedAt(regionId),
				issuedAt -> midTerms.replaceLand(regionId, issuedAt, kma.fetchMidLand(regionId, issuedAt),
						clock.instant()),
				preRefresh);
	}

	public Outcome ensureMidTemperature(String regionId, boolean preRefresh) {
		return coordinator.ensure(CollectionKind.MID_TEMPERATURE, regionId,
				() -> midTerms.latestTemperatureIssuedAt(regionId),
				issuedAt -> midTerms.replaceTemperature(regionId, issuedAt,
						kma.fetchMidTemperature(regionId, issuedAt), clock.instant()),
				preRefresh);
	}

	public Outcome ensureUv(String areaNo, boolean preRefresh) {
		return coordinator.ensure(CollectionKind.UV, areaNo, () -> uv.latestIssuedAt(areaNo),
				issuedAt -> uv.replace(areaNo, issuedAt, kma.fetchUv(areaNo, issuedAt), clock.instant()), preRefresh);
	}

	/**
	 * 측정소의 최근 24시간 측정을 받아 저장한다. 받은 최신 측정이 기다리던 시각보다 이르면 미제공으로 기록해
	 * not-ready-retry-interval 뒤에 다시 받는다(대기질 갱신 주기 = 매시 + weather.air.availability-delay, legacy 04-#12).
	 */
	public Outcome ensureAir(String station, boolean preRefresh) {
		return coordinator.ensure(CollectionKind.AIR, station, () -> air.latestMeasuredAt(station), target -> {
			List<SourceData.AirMeasurement> measurements = airKorea.fetchMeasurements(station);
			air.saveMeasurements(station, measurements, clock.instant());
			if (measurements.isEmpty() || measurements.get(0).measuredAt().isBefore(target)) {
				throw new SourceException.NotReady("에어코리아 " + target + " 측정 미제공");
			}
		}, preRefresh);
	}

}
