package kr.weathertalk.weather.internal.collect;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import kr.weathertalk.weather.internal.fieldreport.FieldReportCleanupService;
import kr.weathertalk.weather.internal.region.AirStationSync;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * weather 정기 작업 (FR-WEA-07·08·10·11). 회차 단위 공유 잠금으로 인스턴스 중 한 곳만 실행한다(BR-WEA-08).
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
	private final FieldReportCleanupService fieldReportCleanup;
	private final AirStationSync airStationSync;

	WeatherJobs(PreRefreshService preRefresh, RetentionCleanupService cleanup,
			FieldReportCleanupService fieldReportCleanup, AirStationSync airStationSync) {
		this.preRefresh = preRefresh;
		this.cleanup = cleanup;
		this.fieldReportCleanup = fieldReportCleanup;
		this.airStationSync = airStationSync;
	}

	/**
	 * 에어코리아 측정소 목록 동기화: 매시 확인하고 weather.air.station-sync-interval이 지났거나 목록이 비었을 때만 받는다
	 * (주기가 설정값이라 예약 시각을 고정하지 않는다. 실패하면 다음 시에 다시 시도).
	 */
	@Scheduled(cron = "0 7 * * * *", zone = "Asia/Seoul")
	@SchedulerLock(name = "weather.air-station-sync", lockAtMostFor = "PT10M", lockAtLeastFor = "PT20S")
	void airStationSync() {
		airStationSync.syncIfDue();
	}

	/** 기동 시 측정소 목록이 비어 있으면 1회 받는다. 기동을 막지 않도록 별도 스레드에서 돈다. */
	@EventListener(ApplicationReadyEvent.class)
	void airStationSyncOnStartup() {
		Thread.ofVirtual().name("weather-air-station-sync").start(airStationSync::ensureListed);
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

	@Scheduled(cron = "0 47 * * * *", zone = "Asia/Seoul")
	@SchedulerLock(name = "weather.field-report-cleanup", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
	void fieldReportCleanup() {
		fieldReportCleanup.cleanup();
	}

}
