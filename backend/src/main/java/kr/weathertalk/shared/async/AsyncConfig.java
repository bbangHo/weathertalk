package kr.weathertalk.shared.async;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 모듈 간 이벤트 구독({@code @ApplicationModuleListener})을 발행 트랜잭션 커밋 후 별도 스레드에서 실행한다.
 * 구독 처리가 실패해도 발행 요청(예: 탈퇴)은 이미 커밋됐고, 미완료 발행은 이벤트 발행 저장소에 남아 재발행된다(ADR 0002).
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
class AsyncConfig {

}
