package kr.weathertalk.community.internal;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

import kr.weathertalk.community.internal.config.CommunityProperties;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * 이미지 파일 정리 정기 작업 (FR-COM-14). community.image.cleanup-interval마다, 인스턴스 중 한 곳에서만 실행한다(contracts.md §4).
 * 주기가 운영 파라미터(Duration)라 {@code @Scheduled} 문자열 대신 등록기로 넣는다. 첫 실행도 한 주기 뒤라 기동 직후 정리하지 않는다.
 */
@Component
class PostImageCleanupJob {

	private final PostImageService images;

	PostImageCleanupJob(PostImageService images) {
		this.images = images;
	}

	@SchedulerLock(name = "community.image-cleanup", lockAtMostFor = "PT30M", lockAtLeastFor = "PT30S")
	public void run() {
		images.cleanup();
	}

	@Configuration(proxyBeanMethods = false)
	static class Registration implements SchedulingConfigurer {

		private final PostImageCleanupJob job;
		private final CommunityProperties properties;

		Registration(PostImageCleanupJob job, CommunityProperties properties) {
			this.job = job;
			this.properties = properties;
		}

		@Override
		public void configureTasks(ScheduledTaskRegistrar registrar) {
			var interval = properties.image().cleanupInterval();
			registrar.addFixedDelayTask(new org.springframework.scheduling.config.FixedDelayTask(job::run, interval,
					interval));
		}

	}

}
