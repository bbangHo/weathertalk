package kr.weathertalk.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

import kr.weathertalk.member.MemberApi;
import kr.weathertalk.neighborhood.NeighborhoodApi;
import kr.weathertalk.notification.internal.config.NotificationProperties;

/**
 * 테스트 발송(FR-NOTI-15)은 운영(prod)에 등록되지 않는다 — 개발 전용 프로파일 + notification.test.enabled(prod yml에서 false 고정)
 * (contracts.md §4 개발용 기능, legacy 01-#21·05 확인 필요 4).
 */
class TestSendRegistrationTests {

	final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withInitializer(new ConfigDataApplicationContextInitializer())
		.withBean(DeviceService.class, () -> mock(DeviceService.class))
		.withBean(DailyWeatherAlerts.class, () -> mock(DailyWeatherAlerts.class))
		.withBean(MessageTemplates.class, () -> mock(MessageTemplates.class))
		.withBean(Dispatcher.class, () -> mock(Dispatcher.class))
		.withBean(NeighborhoodApi.class, () -> mock(NeighborhoodApi.class))
		.withBean(MemberApi.class, () -> mock(MemberApi.class))
		.withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
		.withBean(NotificationProperties.class, NotificationPropertiesFixture::defaults)
		.withBean(Clock.class, Clock::systemUTC)
		.withUserConfiguration(TestSendService.class);

	@Test
	void testSendIsNotRegisteredInProd_01_21() {
		runner.withPropertyValues("spring.profiles.active=prod", "member.token.secret=operations-provided-secret-0123456789")
			.run(context -> assertThat(context).doesNotHaveBean(TestSendService.class));
	}

	@Test
	void testSendIsRegisteredInLocal() {
		runner.withPropertyValues("spring.profiles.active=local")
			.run(context -> assertThat(context).hasSingleBean(TestSendService.class));
	}

}
