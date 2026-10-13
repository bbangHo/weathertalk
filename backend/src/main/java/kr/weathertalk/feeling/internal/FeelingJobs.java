package kr.weathertalk.feeling.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import kr.weathertalk.feeling.internal.config.FeelingProperties;
import kr.weathertalk.shared.time.TimeConfig;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * feeling 정기 작업: 체감 매핑 재계산(FR-FEL-06, feeling.mapping.schedule), 민감도 보정 제안 판정(FR-FEL-07,
 * feeling.calibration.schedule — 재계산 직후), 미확보 스냅샷 백필(BR-FEL-05, feeling.snapshot.backfill-interval).
 * 인스턴스 중 한 곳에서만 실행한다(ShedLock, contracts.md §4). 주기가 운영 파라미터라 {@code @Scheduled} 대신 등록기로 넣는다.
 * 테스트에서는 feeling.jobs.enabled=false로 끄고 서비스 메서드를 직접 부른다.
 */
@Component
@ConditionalOnProperty(name = "feeling.jobs.enabled", havingValue = "true", matchIfMissing = true)
class FeelingJobs {

	private final FeelingMappingRecalculation recalculation;
	private final FeelingCalibrationService calibration;
	private final FeelingSnapshotBackfill backfill;

	FeelingJobs(FeelingMappingRecalculation recalculation, FeelingCalibrationService calibration,
			FeelingSnapshotBackfill backfill) {
		this.recalculation = recalculation;
		this.calibration = calibration;
		this.backfill = backfill;
	}

	@SchedulerLock(name = "feeling.mapping-recalculation", lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
	public void recalculateMapping() {
		recalculation.recalculate();
	}

	@SchedulerLock(name = "feeling.calibration-judgement", lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
	public void judgeCalibration() {
		calibration.judgeAll();
	}

	@SchedulerLock(name = "feeling.snapshot-backfill", lockAtMostFor = "PT30M", lockAtLeastFor = "PT30S")
	public void backfillSnapshots() {
		backfill.backfill();
	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(name = "feeling.jobs.enabled", havingValue = "true", matchIfMissing = true)
	static class Registration implements SchedulingConfigurer {

		private final FeelingJobs jobs;
		private final FeelingProperties properties;

		Registration(FeelingJobs jobs, FeelingProperties properties) {
			this.jobs = jobs;
			this.properties = properties;
		}

		@Override
		public void configureTasks(ScheduledTaskRegistrar registrar) {
			registrar.addCronTask(new CronTask(jobs::recalculateMapping,
					new CronTrigger(properties.mapping().schedule(), TimeConfig.SEOUL)));
			registrar.addCronTask(new CronTask(jobs::judgeCalibration,
					new CronTrigger(properties.calibration().schedule(), TimeConfig.SEOUL)));
			var interval = properties.snapshot().backfillInterval();
			registrar.addFixedDelayTask(new FixedDelayTask(jobs::backfillSnapshots, interval, interval));
		}

	}

}
