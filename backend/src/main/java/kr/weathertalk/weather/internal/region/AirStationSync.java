package kr.weathertalk.weather.internal.region;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import kr.weathertalk.weather.internal.collect.CollectLock;
import kr.weathertalk.weather.internal.collect.CollectStateRepository;
import kr.weathertalk.weather.internal.collect.CollectStateRepository.CollectState;
import kr.weathertalk.weather.internal.collect.CollectStateRepository.Result;
import kr.weathertalk.weather.internal.collect.CollectionKind;
import kr.weathertalk.weather.internal.config.WeatherProperties;
import kr.weathertalk.weather.internal.source.AirKoreaSource;
import kr.weathertalk.weather.internal.source.SourceData;
import kr.weathertalk.weather.internal.source.SourceException;
import kr.weathertalk.weather.internal.store.AirStationStore;

/**
 * 에어코리아 측정소 목록 동기화 (03 §3.6, 구현 메모 §13). 전국 목록(getMsrstnList)을 받아 통째로 반영한다 — 신설은 추가,
 * 목록에서 빠지면 폐지 표시. 마지막 성공이 weather.air.station-sync-interval보다 오래됐거나 목록이 비어 있으면 받는다.
 * 실패하면 weather.collect.failure-backoff 동안 다시 부르지 않는다. 인스턴스가 여럿이어도 수집 점유로 한 곳만 부른다.
 */
@Component
public class AirStationSync {

	private static final Logger log = LoggerFactory.getLogger(AirStationSync.class);

	static final String UNIT = "ALL";

	/** 점유 키를 고정하기 위한 값(발표 시각이 없는 수집). */
	private static final Instant LOCK_TARGET = Instant.EPOCH;

	/** 다른 수집자를 기다릴 때 확인 간격(대기 루프 폴링 주기). */
	private static final long WAIT_POLL_MILLIS = 50;

	private final AirKoreaSource source;
	private final AirStationStore store;
	private final CollectStateRepository states;
	private final CollectLock lock;
	private final WeatherProperties properties;
	private final Clock clock;

	public AirStationSync(AirKoreaSource source, AirStationStore store, CollectStateRepository states, CollectLock lock,
			WeatherProperties properties, Clock clock) {
		this.source = source;
		this.store = store;
		this.states = states;
		this.lock = lock;
		this.properties = properties;
		this.clock = clock;
	}

	/** 정기 작업·기동 시: 목록이 비었거나 동기화 주기가 지났으면 받는다. */
	public void syncIfDue() {
		if (due()) {
			sync(this::due);
		}
	}

	/**
	 * 요청 경로: 목록이 비어 있을 때만 받는다(기동 직후·첫 배포). 다른 곳이 받는 중이면 weather.collect.wait-timeout까지 기다린다.
	 *
	 * @return 운영 중 측정소가 있는가
	 */
	public boolean ensureListed() {
		if (store.activeCount() > 0) {
			return true;
		}
		sync(() -> store.activeCount() == 0);
		return store.activeCount() > 0;
	}

	private boolean due() {
		Instant now = clock.instant();
		boolean recent = states.find(CollectionKind.AIR_STATIONS, UNIT)
			.map(CollectState::lastSuccessIssuedAt)
			.filter(at -> at.plus(properties.air().stationSyncInterval()).isAfter(now))
			.isPresent();
		return !recent || store.activeCount() == 0;
	}

	/** @param stillNeeded 점유를 얻은 뒤 다시 확인한다(그 사이 다른 수집자가 끝냈을 수 있다) */
	private void sync(BooleanSupplier stillNeeded) {
		if (!source.configured() || backingOff()) {
			return;
		}
		try (CollectLock.Handle handle = lock.tryAcquire(CollectionKind.AIR_STATIONS, UNIT, LOCK_TARGET,
				properties.collect().lockLease())) {
			if (handle.status() == CollectLock.Status.HELD_BY_OTHER) {
				awaitOther(handle.key());
				return;
			}
			if (stillNeeded.getAsBoolean() && !backingOff()) {
				collect(clock.instant());
			}
		}
	}

	private void collect(Instant now) {
		try {
			List<SourceData.AirStation> stations = source.fetchStations();
			AirStationStore.SyncResult result = store.replaceAll(stations, now);
			states.record(CollectionKind.AIR_STATIONS, UNIT, now, now, Result.SUCCESS, now);
			log.info("에어코리아 측정소 목록 동기화: 운영 {}곳(신설 {}, 폐지 {}), 좌표 없음 {}곳", result.active(), result.added(),
					result.closed(), stations.stream().filter(s -> s.longitude() == null).count());
		}
		catch (SourceException.KeyMissing e) {
			log.warn("측정소 목록 동기화 생략: {}", e.getMessage());
			states.record(CollectionKind.AIR_STATIONS, UNIT, now, null, Result.KEY_MISSING, now);
		}
		catch (SourceException e) {
			// 원인 메시지에는 인증키가 든 URL이 있을 수 있어 싣지 않는다
			log.warn("측정소 목록 동기화 실패: {}", e.getMessage());
			states.record(CollectionKind.AIR_STATIONS, UNIT, now, null, Result.FAILURE, now);
		}
	}

	private boolean backingOff() {
		Instant now = clock.instant();
		CollectState state = states.find(CollectionKind.AIR_STATIONS, UNIT).orElse(null);
		return state != null && state.lastAttemptAt() != null
				&& (state.lastResult() == Result.FAILURE || state.lastResult() == Result.KEY_MISSING)
				&& state.lastAttemptAt().plus(properties.collect().failureBackoff()).isAfter(now);
	}

	/** 실시간 기준 wait-timeout까지 기다린다. 넘으면 저장분(없을 수 있음)으로 진행한다. */
	private void awaitOther(String key) {
		long deadline = System.nanoTime() + properties.collect().waitTimeout().toNanos();
		while (System.nanoTime() < deadline && lock.isHeld(key)) {
			try {
				Thread.sleep(WAIT_POLL_MILLIS);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

}
