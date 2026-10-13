package kr.weathertalk.member.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import kr.weathertalk.member.internal.config.MemberProperties;
import kr.weathertalk.shared.time.TimeConfig;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * member 정기 작업: 재시도 대기 중인 제공자 연결 해제 실행(FR-MEM-09 규칙 4, member.external-unlink.poll-interval),
 * 실패 작업 자격 파기·만료 가입 티켓 정리(매일 03:20). 인스턴스 중 한 곳에서만 돈다(ShedLock — 작업 점유와 함께 이중 장치).
 * 테스트에서는 member.jobs.enabled=false로 끄고 서비스 메서드를 직접 부른다.
 */
@Component
@ConditionalOnProperty(name = "member.jobs.enabled", havingValue = "true", matchIfMissing = true)
class MemberJobs {

	private final ExternalUnlinkService unlinks;

	MemberJobs(ExternalUnlinkService unlinks) {
		this.unlinks = unlinks;
	}

	@SchedulerLock(name = "member.external-unlink", lockAtMostFor = "PT10M", lockAtLeastFor = "PT5S")
	public void processUnlinks() {
		unlinks.processDue();
	}

	@SchedulerLock(name = "member.external-unlink-purge", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
	public void purge() {
		unlinks.purgeExpired();
	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(name = "member.jobs.enabled", havingValue = "true", matchIfMissing = true)
	static class Registration implements SchedulingConfigurer {

		private final MemberJobs jobs;
		private final MemberProperties properties;

		Registration(MemberJobs jobs, MemberProperties properties) {
			this.jobs = jobs;
			this.properties = properties;
		}

		@Override
		public void configureTasks(ScheduledTaskRegistrar registrar) {
			var interval = properties.externalUnlink().pollInterval();
			registrar.addFixedDelayTask(new FixedDelayTask(jobs::processUnlinks, interval, interval));
			registrar.addCronTask(new CronTask(jobs::purge, new CronTrigger("0 20 3 * * *", TimeConfig.SEOUL)));
		}

	}

}
