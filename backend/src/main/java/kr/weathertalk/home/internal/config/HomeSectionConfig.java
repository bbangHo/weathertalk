package kr.weathertalk.home.internal.config;

import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import kr.weathertalk.home.internal.SectionRunner;

/**
 * 섹션 조회 실행기. 섹션 조회는 대부분 하위 모듈의 I/O 대기라 가상 스레드로 섹션마다 하나씩 띄운다.
 * 실행기를 빈으로 노출하지 않는 이유: Executor 빈이 생기면 Boot 기본 작업 실행기(이벤트 구독 비동기 처리용)가 등록되지 않는다.
 */
@Configuration(proxyBeanMethods = false)
class HomeSectionConfig {

	@Bean(destroyMethod = "close")
	SectionRunner homeSectionRunner(HomeProperties properties) {
		return new SectionRunner(Executors.newVirtualThreadPerTaskExecutor(), properties.sectionTimeout());
	}

}
