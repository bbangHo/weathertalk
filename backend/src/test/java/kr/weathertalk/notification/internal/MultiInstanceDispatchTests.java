package kr.weathertalk.notification.internal;

import static kr.weathertalk.neighborhood.NeighborhoodTestData.N1;
import static kr.weathertalk.weather.WeatherStubs.kst;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import kr.weathertalk.IntegrationTest;
import kr.weathertalk.WeatherTalkApplication;
import kr.weathertalk.neighborhood.ForecastGrid;
import kr.weathertalk.notification.NotificationTestBase;
import kr.weathertalk.notification.internal.push.PushMessage;
import kr.weathertalk.notification.internal.push.PushResult;
import kr.weathertalk.notification.internal.push.PushSender;
import kr.weathertalk.weather.FieldReportPrecipitationReached;
import kr.weathertalk.weather.PrecipitationType;

/**
 * 다중 인스턴스 중복 발송 0건 (BR-NOTI-10, legacy 05-#7, implementation-plan §4 다중 인스턴스). 애플리케이션 컨텍스트 2개가 같은 DB·Redis를 쓰고,
 * 같은 정기 날씨 알림 회차·같은 이벤트·같은 재시도 건을 동시에 처리해도 기기마다 정확히 1건만 나간다(분산 락이 없어도 회차 기록·발송 키 유일성·
 * 원자적 쿨다운 선점이 막는다). 푸시 발송기는 두 인스턴스가 하나를 공유해 전체 발송 수를 센다.
 */
@IntegrationTest
class MultiInstanceDispatchTests extends NotificationTestBase {

	private static ConfigurableApplicationContext secondInstance;

	@Autowired
	PostgreSQLContainer postgres;

	@Autowired
	@Qualifier("redisContainer")
	GenericContainer<?> redisContainer;

	@Autowired
	DailyWeatherAlerts daily;

	@Autowired
	EventAlerts alerts;

	@Autowired
	Dispatcher dispatcher;

	@BeforeEach
	void startSecondInstance() {
		if (secondInstance != null) {
			return;
		}
		Map<String, Object> properties = new HashMap<>();
		properties.put("spring.datasource.url", postgres.getJdbcUrl());
		properties.put("spring.datasource.username", postgres.getUsername());
		properties.put("spring.datasource.password", postgres.getPassword());
		properties.put("spring.data.redis.host", redisContainer.getHost());
		properties.put("spring.data.redis.port", redisContainer.getMappedPort(6379));
		properties.put("spring.docker.compose.enabled", false);
		properties.put("spring.modulith.events.republish-outstanding-events-on-restart", false);
		properties.put("weather.source.kma.base-url", server.baseUrl() + "/kma");
		properties.put("weather.source.kma.service-key", "test-kma-key");
		properties.put("weather.source.air-korea.base-url", server.baseUrl() + "/air");
		properties.put("weather.source.air-korea.service-key", "test-air-key");
		Clock sharedClock = clock;
		PushSender sharedSender = pushes;
		secondInstance = new SpringApplicationBuilder(WeatherTalkApplication.class).web(WebApplicationType.NONE)
			.profiles("test")
			.properties(properties)
			.initializers(context -> {
				GenericApplicationContext generic = (GenericApplicationContext) context;
				generic.registerBean("sharedTestClock", Clock.class, () -> sharedClock, bd -> bd.setPrimary(true));
				generic.registerBean("sharedPushSender", PushSender.class, () -> sharedSender,
						bd -> bd.setPrimary(true));
			})
			.run();
	}

	@AfterAll
	static void stopSecondInstance() {
		if (secondInstance != null) {
			secondInstance.close();
			secondInstance = null;
		}
	}

	private List<DeviceHandle> devices(int count, boolean daily) throws Exception {
		List<DeviceHandle> devices = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			DeviceHandle device = guestDevice();
			subscribeFixed(device, N1, daily ? List.of("06:30") : null, false, true);
			devices.add(device);
		}
		return devices;
	}

	private static void together(Runnable a, Runnable b) throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		CompletableFuture<Void> first = CompletableFuture.runAsync(() -> await(start, a));
		CompletableFuture<Void> second = CompletableFuture.runAsync(() -> await(start, b));
		start.countDown();
		CompletableFuture.allOf(first, second).get();
	}

	private static void await(CountDownLatch start, Runnable job) {
		try {
			start.await();
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return;
		}
		job.run();
	}

	private Map<String, Long> sentPerToken() {
		return pushes.sent().stream().collect(Collectors.groupingBy(PushMessage::token, Collectors.counting()));
	}

	@Test
	void sameDailyRoundOnTwoInstancesSendsOncePerDevice_05_7() throws Exception {
		clock.set(kst("2026-10-13T06:30").plusSeconds(10));
		ForecastGrid grid = gridOf(N1);
		stubs.observation(grid, kst("2026-10-13T06:00"), 18.0);
		stubs.shortTerm(grid, kst("2026-10-13T05:00"), 24);
		// 날씨는 먼저 수집해 둔다(두 번째 인스턴스의 첫 외부 호출 준비 시간이 테스트용 제한시간을 넘겨 '날씨 없음'이 되지 않게)
		weatherApi.findCurrentObservation(N1);
		weatherApi.findHourlyForecast(N1, 12);
		List<DeviceHandle> devices = devices(40, true);
		DailyWeatherAlerts other = secondInstance.getBean(DailyWeatherAlerts.class);

		together(daily::tick, other::tick);

		assertThat(deliveries("DAILY_WEATHER")).extracting(r -> r.get("status")).containsOnly("SUCCEEDED");
		assertThat(sentPerToken()).hasSize(devices.size()).allSatisfy((token, count) -> assertThat(count).isEqualTo(1));
		assertThat(jdbc.sql("SELECT count(*) FROM noti_daily_round WHERE round_at = :r")
			.param("r", java.sql.Timestamp.from(kst("2026-10-13T06:30")))
			.query(Integer.class)
			.single()).isEqualTo(1);
	}

	@Test
	void sameEventOnTwoInstancesSendsOncePerDevice() throws Exception {
		clock.set(kst("2026-10-13T12:00"));
		List<DeviceHandle> devices = devices(40, false);
		FieldReportPrecipitationReached event = new FieldReportPrecipitationReached(UUID.randomUUID(), N1,
				PrecipitationType.RAIN, clock.instant(), 3, List.of());
		EventAlerts other = secondInstance.getBean(EventAlerts.class);

		together(() -> alerts.fieldReportPrecipitation(event), () -> other.fieldReportPrecipitation(event));
		// 다른 이벤트 ID로 같은 동네 알림이 동시에 와도 쿨다운 선점이 원자적이라 기기당 1건
		FieldReportPrecipitationReached again = new FieldReportPrecipitationReached(UUID.randomUUID(), N1,
				PrecipitationType.SNOW, clock.instant(), 4, List.of());
		FieldReportPrecipitationReached again2 = new FieldReportPrecipitationReached(UUID.randomUUID(), N1,
				PrecipitationType.RAIN, clock.instant(), 5, List.of());
		together(() -> alerts.fieldReportPrecipitation(again), () -> other.fieldReportPrecipitation(again2));

		assertThat(sentPerToken()).hasSize(devices.size()).allSatisfy((token, count) -> assertThat(count).isEqualTo(1));
	}

	@Test
	void retriesClaimedByOnlyOneInstance() throws Exception {
		clock.set(kst("2026-10-13T12:00"));
		List<DeviceHandle> devices = devices(20, false);
		devices.forEach(d -> pushes.script(d.token(), PushResult.transientFailure("UNAVAILABLE")));
		alerts.fieldReportPrecipitation(new FieldReportPrecipitationReached(UUID.randomUUID(), N1,
				PrecipitationType.RAIN, clock.instant(), 3, List.of()));
		assertThat(pushes.sent()).isEmpty();

		clock.advance(java.time.Duration.ofSeconds(30));
		Dispatcher other = secondInstance.getBean(Dispatcher.class);
		together(dispatcher::processDue, other::processDue);

		Map<String, Long> sent = sentPerToken();
		assertThat(sent).hasSize(devices.size()).allSatisfy((token, count) -> assertThat(count).isEqualTo(1));
		assertThat(deliveries("FIELD_REPORT_PRECIPITATION")).extracting(r -> r.get("status")).containsOnly("SUCCEEDED");
	}

}
