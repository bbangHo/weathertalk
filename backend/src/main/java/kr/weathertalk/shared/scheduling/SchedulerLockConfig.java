package kr.weathertalk.shared.scheduling;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;

/**
 * 정기 작업 잠금 (contracts.md §4 정기 작업: 다중 인스턴스에서 회차 단위 공유 잠금으로 한 곳에서만 실행).
 * <p>
 * 정기 작업 메서드에 {@code @Scheduled}와 함께 {@code @SchedulerLock(name = "<모듈>.<작업>", lockAtMostFor = ..., lockAtLeastFor = ...)}을 붙인다.
 * {@code lockAtLeastFor}는 인스턴스 간 시계 차이로 같은 회차가 두 번 도는 것을 막을 만큼(수 초 이상) 준다.
 * <p>
 * Redis 공급자를 고른 이유: Redis는 예보 캐시·수집 점유·요청 식별자에 이미 쓰는 필수 인프라라(결정 P3) 잠금용 테이블·마이그레이션이
 * 따로 필요 없고, 만료(TTL)로 죽은 인스턴스의 잠금이 저절로 풀린다. 잠금을 잃어도(Redis 재시작) 최악은 한 회차 중복 실행이며,
 * 중복이 치명적인 작업(알림 발송)은 쿨다운·발송 기록으로 따로 멱등 처리한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableSchedulerLock(defaultLockAtMostFor = "${shared.scheduler-lock.default-lock-at-most-for:PT10M}")
public class SchedulerLockConfig {

	@Bean
	LockProvider lockProvider(RedisConnectionFactory connectionFactory,
			@Value("${spring.application.name:weathertalk}") String environment) {
		return new RedisLockProvider(connectionFactory, environment);
	}

}
