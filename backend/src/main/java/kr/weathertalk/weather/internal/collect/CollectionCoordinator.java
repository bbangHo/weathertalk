package kr.weathertalk.weather.internal.collect;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import kr.weathertalk.weather.internal.collect.CollectStateRepository.CollectState;
import kr.weathertalk.weather.internal.collect.CollectStateRepository.Result;
import kr.weathertalk.weather.internal.config.WeatherProperties;
import kr.weathertalk.weather.internal.source.SourceException;

/**
 * 요청 시 수집·사전 갱신의 단일 수집 경로 (FR-WEA-06, FR-WEA-07 3~4단계, BR-WEA-06·07).
 * <ol>
 * <li>저장된 최신 발표분이 받을 수 있는 최신 발표 시각이면 수집하지 않는다.</li>
 * <li>마지막 시도가 실패이고 failure-backoff 이내면, 또는 같은 발표분이 미제공이었고 not-ready-retry-interval 이내면 수집하지 않는다
 * (매 요청 재시도 폭주 방지, legacy 04-#9).</li>
 * <li>점유를 얻은 한 곳만 외부를 호출한다. 못 얻으면 wait-timeout까지 결과를 기다리고 저장값으로 응답한다.</li>
 * <li>최신 발표분이 비어 있고 저장된 정상값이 전혀 없을 때만 직전 발표분을 1회 받아 '대체'로 기록한다.</li>
 * </ol>
 * 외부 실패는 조회 오류가 아니다: 상태만 기록하고 호출자는 저장값으로 응답한다.
 */
@Component
public class CollectionCoordinator {

	private static final Logger log = LoggerFactory.getLogger(CollectionCoordinator.class);

	/** 점유 대기 중 확인 간격. 시각 판정이 아니라 대기 루프의 폴링 주기다. */
	private static final long WAIT_POLL_MILLIS = 50;

	public enum Outcome {

		ALREADY_FRESH, COLLECTED, SUBSTITUTED, SKIPPED, WAITED, NOT_READY, FAILED

	}

	private final CollectStateRepository states;
	private final CollectLock lock;
	private final IssueTimes issueTimes;
	private final WeatherProperties properties;
	private final Clock clock;

	public CollectionCoordinator(CollectStateRepository states, CollectLock lock, IssueTimes issueTimes,
			WeatherProperties properties, Clock clock) {
		this.states = states;
		this.lock = lock;
		this.issueTimes = issueTimes;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * @param latestStored 이 수집 단위에 저장된 가장 최근 발표(관측·측정) 시각
	 * @param collect 그 발표 시각을 받아 통째로 교체 저장한다. 실패는 {@link SourceException}
	 * @param preRefresh 사전 갱신이면 not-ready-max-wait를 넘긴 발표분은 그 회차에서 포기한다
	 */
	public Outcome ensure(CollectionKind kind, String unitKey, Supplier<Optional<Instant>> latestStored,
			Consumer<Instant> collect, boolean preRefresh) {
		Instant now = clock.instant();
		Instant target = issueTimes.latest(kind, now);
		if (isFresh(latestStored, target)) {
			return Outcome.ALREADY_FRESH;
		}
		if (blocked(kind, states.find(kind, unitKey).orElse(null), target, now, preRefresh)) {
			return Outcome.SKIPPED;
		}
		try (CollectLock.Handle handle = lock.tryAcquire(kind, unitKey, target, properties.collect().lockLease())) {
			if (handle.status() == CollectLock.Status.HELD_BY_OTHER) {
				awaitOther(handle.key(), latestStored, target);
				return Outcome.WAITED;
			}
			// 점유를 얻는 사이 다른 수집자가 끝냈을 수 있다
			if (isFresh(latestStored, target)
					|| blocked(kind, states.find(kind, unitKey).orElse(null), target, now, preRefresh)) {
				return Outcome.ALREADY_FRESH;
			}
			return collect(kind, unitKey, target, latestStored, collect, now);
		}
	}

	private Outcome collect(CollectionKind kind, String unitKey, Instant target,
			Supplier<Optional<Instant>> latestStored, Consumer<Instant> collect, Instant now) {
		try {
			collect.accept(target);
			states.record(kind, unitKey, target, target, Result.SUCCESS, now);
			return Outcome.COLLECTED;
		}
		catch (SourceException.NotReady notReady) {
			if (latestStored.get().isEmpty()) {
				Instant previous = issueTimes.previous(kind, target);
				try {
					collect.accept(previous);
					states.record(kind, unitKey, target, previous, Result.SUBSTITUTE, now);
					log.info("{} {} 최신 발표분({}) 미제공 — 직전 발표분({})으로 대체", kind, unitKey, target, previous);
					return Outcome.SUBSTITUTED;
				}
				catch (SourceException e) {
					// 직전 발표분도 못 받으면 미제공으로 기록하고 저장값(없음)으로 응답
				}
			}
			states.record(kind, unitKey, target, null, Result.NOT_READY, now);
			return Outcome.NOT_READY;
		}
		catch (SourceException.KeyMissing e) {
			log.warn("{} {} 수집 생략: {}", kind, unitKey, e.getMessage());
			states.record(kind, unitKey, target, null, Result.KEY_MISSING, now);
			return Outcome.FAILED;
		}
		catch (SourceException e) {
			// 원인 메시지에는 인증키가 든 URL이 있을 수 있어 싣지 않는다
			log.warn("{} {} 수집 실패({}): {}", kind, unitKey, target, e.getMessage());
			states.record(kind, unitKey, target, null, Result.FAILURE, now);
			return Outcome.FAILED;
		}
	}

	private boolean blocked(CollectionKind kind, CollectState state, Instant target, Instant now, boolean preRefresh) {
		if (state == null || state.lastResult() == null || state.lastAttemptAt() == null) {
			return false;
		}
		return switch (state.lastResult()) {
			case SUCCESS -> false;
			case FAILURE, KEY_MISSING -> state.lastAttemptAt().plus(properties.collect().failureBackoff()).isAfter(now);
			case NOT_READY, SUBSTITUTE -> {
				if (!target.equals(state.lastAttemptTarget())) {
					yield false;
				}
				if (preRefresh && now.isAfter(
						issueTimes.availableAt(kind, target).plus(properties.collect().notReadyMaxWait()))) {
					yield true;
				}
				yield state.lastAttemptAt().plus(properties.collect().notReadyRetryInterval()).isAfter(now);
			}
		};
	}

	private static boolean isFresh(Supplier<Optional<Instant>> latestStored, Instant target) {
		return latestStored.get().map(stored -> !stored.isBefore(target)).orElse(false);
	}

	/** 다른 요청·인스턴스의 수집이 끝나기를 기다린다(실시간 기준 wait-timeout). 넘으면 저장값으로 응답한다. */
	private void awaitOther(String key, Supplier<Optional<Instant>> latestStored, Instant target) {
		long deadline = System.nanoTime() + properties.collect().waitTimeout().toNanos();
		while (System.nanoTime() < deadline) {
			if (!lock.isHeld(key) || isFresh(latestStored, target)) {
				return;
			}
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
