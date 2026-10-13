package kr.weathertalk.weather.internal.collect;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import kr.weathertalk.neighborhood.ForecastGrid;
import kr.weathertalk.neighborhood.Neighborhood;
import kr.weathertalk.neighborhood.NeighborhoodApi;
import kr.weathertalk.weather.internal.config.WeatherProperties;
import kr.weathertalk.weather.internal.region.AirStationSync;
import kr.weathertalk.weather.internal.region.MidTermRegions;
import kr.weathertalk.weather.internal.region.UvAreas;
import kr.weathertalk.weather.internal.source.AirKoreaSource;
import kr.weathertalk.weather.internal.source.KmaSource;

/**
 * 활성 예보 격자 사전 갱신 (FR-WEA-07)과 중기예보 전국 정기 수집 (FR-WEA-08).
 * <p>
 * 대상은 neighborhood의 활성 예보 격자·활성 동네뿐이며 개수 제한이 없다(ADR 0005). 대상별 수집은 요청 시 수집과 같은 경로라
 * 같은 점유 규칙으로 서로 중복되지 않는다. 한 대상의 실패는 다른 대상에 영향이 없다. 동시 외부 호출 수는 weather.collect.max-concurrency.
 * 발표분이 아직 없으면 not-ready-retry-interval 뒤 다음 실행에서 다시 시도하고 not-ready-max-wait를 넘기면 그 회차를 포기한다.
 */
@Service
public class PreRefreshService {

	private static final Logger log = LoggerFactory.getLogger(PreRefreshService.class);

	private final NeighborhoodApi neighborhoodApi;
	private final WeatherCollector collector;
	private final AirStationSync stationSync;
	private final MidTermRegions midTermRegions;
	private final KmaSource kma;
	private final AirKoreaSource airKorea;
	private final WeatherProperties properties;

	public PreRefreshService(NeighborhoodApi neighborhoodApi, WeatherCollector collector,
			AirStationSync stationSync, MidTermRegions midTermRegions, KmaSource kma, AirKoreaSource airKorea,
			WeatherProperties properties) {
		this.neighborhoodApi = neighborhoodApi;
		this.collector = collector;
		this.stationSync = stationSync;
		this.midTermRegions = midTermRegions;
		this.kma = kma;
		this.airKorea = airKorea;
		this.properties = properties;
	}

	/** 활성 예보 격자의 실황·단기예보, 활성 동네의 자외선·대기질. */
	public void refreshActive() {
		List<Runnable> tasks = new ArrayList<>();
		if (kma.configured()) {
			for (ForecastGrid grid : neighborhoodApi.findActiveForecastGrids()) {
				tasks.add(() -> collector.ensureObservation(grid, true));
				tasks.add(() -> collector.ensureShortTerm(grid, true));
			}
		}
		else {
			log.warn("기상청 인증키(weather.source.kma.service-key) 미설정 — 기상청 사전 갱신을 건너뜁니다.");
		}
		Collection<Neighborhood> active = neighborhoodApi.findNeighborhoods(neighborhoodApi.findActiveNeighborhoods())
			.values();
		if (kma.configured()) {
			Set<String> areas = new LinkedHashSet<>();
			active.forEach(n -> areas.add(UvAreas.areaNo(n.id())));
			areas.forEach(area -> tasks.add(() -> collector.ensureUv(area, true)));
		}
		if (airKorea.configured()) {
			// 대기질은 전국 1회 수집이 모든 활성 동네를 덮는다. 활성 동네가 없으면 부르지 않는다
			if (!active.isEmpty()) {
				tasks.add(() -> {
					if (stationSync.ensureListed()) {
						collector.ensureAir(true);
					}
				});
			}
		}
		else {
			log.warn("에어코리아 인증키(weather.source.air-korea.service-key) 미설정 — 대기질 사전 갱신을 건너뜁니다.");
		}
		runAll(tasks);
	}

	/** 모든 중기 예보구역(육상 구역 + 기온 지점). */
	public void refreshMidTerm() {
		if (!kma.configured()) {
			log.warn("기상청 인증키(weather.source.kma.service-key) 미설정 — 중기예보 정기 수집을 건너뜁니다.");
			return;
		}
		List<Runnable> tasks = new ArrayList<>();
		midTermRegions.landRegionIds().forEach(id -> tasks.add(() -> collector.ensureMidLand(id, true)));
		midTermRegions.temperatureRegionIds().forEach(id -> tasks.add(() -> collector.ensureMidTemperature(id, true)));
		runAll(tasks);
	}

	private void runAll(List<Runnable> tasks) {
		Semaphore permits = new Semaphore(properties.collect().maxConcurrency());
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Future<?>> futures = new ArrayList<>();
			for (Runnable task : tasks) {
				futures.add(executor.submit(() -> {
					permits.acquireUninterruptibly();
					try {
						task.run();
					}
					catch (RuntimeException e) {
						log.warn("사전 갱신 대상 실패: {}", e.getMessage());
					}
					finally {
						permits.release();
					}
				}));
			}
			for (Future<?> future : futures) {
				try {
					future.get();
				}
				catch (Exception e) {
					log.warn("사전 갱신 대기 중 오류: {}", e.getMessage());
				}
			}
		}
	}

}
