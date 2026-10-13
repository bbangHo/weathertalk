package kr.weathertalk.weather.internal.collect;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * weather 정기 작업 (FR-WEA-07·08·11). 회차 단위 공유 잠금으로 인스턴스 중 한 곳만 실행한다(BR-WEA-08).
 * <p>
 * 사전 갱신을 발표 시각마다 따로 예약하지 않고 매분 확인하는 이유: 발표 시각 + 제공 지연(*.availability-delay)이 설정값이라
 * 예약 시각을 고정할 수 없고, 미제공 시 회차 내 재시도(not-ready-retry-interval)·포기(not-ready-max-wait)도
 * 같은 판정(수집 상태)으로 처리되기 때문이다. 받을 발표분이 이미 있으면 외부를 호출하지 않는다.
 * 테스트에서는 weather.source.jobs.enabled=false로 끄고 서비스 메서드를 직접 부른다.
 */
@Component
@ConditionalOnProperty(name = "weather.source.jobs.enabled", havingValue = "true", matchIfMissing = true)
class WeatherJobs {

	private final PreRefreshService preRefresh;
	private final RetentionCleanupService cleanup;

	WeatherJobs(PreRefreshService preRefresh, RetentionCleanupService cleanup) {
		this.preRefresh = preRefresh;
		this.cleanup = cleanup;
	}

	@Scheduled(cron = "0 * * * * *", zone = "Asia/Seoul")
	@SchedulerLock(name = "weather.pre-refresh", lockAtMostFor = "PT10M", lockAtLeastFor = "PT20S")
	void preRefresh() {
		preRefresh.refreshActive();
	}

	@Scheduled(cron = "30 * * * * *", zone = "Asia/Seoul")
	@SchedulerLock(name = "weather.mid-term-collect", lockAtMostFor = "PT10M", lockAtLeastFor = "PT20S")
	void midTerm() {
		preRefresh.refreshMidTerm();
	}

	@Scheduled(cron = "0 17 * * * *", zone = "Asia/Seoul")
	@SchedulerLock(name = "weather.retention-cleanup", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
	void cleanup() {
		cleanup.cleanup();
	}

}
