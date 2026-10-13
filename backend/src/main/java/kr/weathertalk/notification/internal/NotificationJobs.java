package kr.weathertalk.notification.internal;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

import kr.weathertalk.shared.time.TimeConfig;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * notification 정기 작업. 회차 단위 공유 잠금으로 한 인스턴스에서만 계획한다(ShedLock) — 잠금이 풀리거나 두 번 돌아도
 * 회차 기록·발송 키 유일성이 중복 발송을 막는다(BR-NOTI-10, legacy 05-#7).
 * <ul>
 * <li>정기 날씨 알림 확인: 매분 0초(회차 시각은 time-unit 정렬, 예열 포함)</li>
 * <li>재시도 대기·심야 보류 건 발송: 30초마다</li>
 * <li>발송 기록 정리 04:10, 장기 미사용 기기 정리 04:20, 알림 구독 동네 대조 04:40 (Asia/Seoul)</li>
 * </ul>
 * 실제 발송은 {@link Dispatcher}의 전용 실행기에서 돌아 다른 모듈 스케줄을 붙잡지 않는다(BR-NOTI-11, legacy 06-#18).
 * 테스트에서는 notification.jobs.enabled=false로 끄고 서비스 메서드를 직접 부른다.
 */
@Component
@ConditionalOnProperty(name = "notification.jobs.enabled", havingValue = "true", matchIfMissing = true)
class NotificationJobs {

	private final DailyWeatherAlerts daily;
	private final Dispatcher dispatcher;
	private final NotificationMaintenance maintenance;
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	NotificationJobs(DailyWeatherAlerts daily, Dispatcher dispatcher, NotificationMaintenance maintenance) {
		this.daily = daily;
		this.dispatcher = dispatcher;
		this.maintenance = maintenance;
	}

	/** 스케줄 스레드는 넘기기만 하고 바로 돌아온다(공용 스케줄 스레드를 붙잡지 않게). 잠금은 넘겨받은 쪽에서 잡는다. */
	public void launch(Runnable job) {
		executor.execute(job);
	}

	@PreDestroy
	void shutdown() {
		executor.shutdownNow();
	}

	@SchedulerLock(name = "notification.daily-weather", lockAtMostFor = "PT9M", lockAtLeastFor = "PT20S")
	public void dailyWeather() {
		daily.tick();
	}

	@SchedulerLock(name = "notification.retry", lockAtMostFor = "PT5M", lockAtLeastFor = "PT5S")
	public void retry() {
		dispatcher.processDue();
	}

	@SchedulerLock(name = "notification.delivery-log-purge", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
	public void purgeDeliveryLog() {
		maintenance.purgeDeliveryLog();
	}

	@SchedulerLock(name = "notification.stale-devices", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
	public void staleDevices() {
		maintenance.cleanUpStaleDevices();
	}

	@SchedulerLock(name = "notification.subscribed-neighborhoods", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
	public void reconcile() {
		maintenance.reconcileSubscribedNeighborhoods();
	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(name = "notification.jobs.enabled", havingValue = "true", matchIfMissing = true)
	static class Registration implements SchedulingConfigurer {

		private final NotificationJobs jobs;

		Registration(NotificationJobs jobs) {
			this.jobs = jobs;
		}

		@Override
		public void configureTasks(ScheduledTaskRegistrar registrar) {
			registrar.addCronTask(new CronTask(() -> jobs.launch(jobs::dailyWeather), new CronTrigger("0 * * * * *", TimeConfig.SEOUL)));
			Duration retry = Duration.ofSeconds(30);
			registrar.addFixedDelayTask(new FixedDelayTask(() -> jobs.launch(jobs::retry), retry, retry));
			registrar.addCronTask(new CronTask(jobs::purgeDeliveryLog, new CronTrigger("0 10 4 * * *", TimeConfig.SEOUL)));
			registrar.addCronTask(new CronTask(jobs::staleDevices, new CronTrigger("0 20 4 * * *", TimeConfig.SEOUL)));
			registrar.addCronTask(new CronTask(jobs::reconcile, new CronTrigger("0 40 4 * * *", TimeConfig.SEOUL)));
		}

	}

}
