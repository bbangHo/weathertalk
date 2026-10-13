package kr.weathertalk.shared.scheduling;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @Scheduled} 활성화. 스케줄 판정 시간대는 각 작업의 {@code zone = "Asia/Seoul"}로 명시한다(contracts.md §4).
 * 잠금 설정({@link SchedulerLockConfig})과 분리한 이유: 잠금만 필요한 테스트 컨텍스트가 스케줄러까지 띄우지 않게 하려고.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfig {

}
