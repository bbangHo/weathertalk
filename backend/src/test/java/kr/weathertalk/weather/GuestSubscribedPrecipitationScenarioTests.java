package kr.weathertalk.weather;

import static kr.weathertalk.neighborhood.NeighborhoodTestData.N7;
import static kr.weathertalk.weather.WeatherStubs.N7_GRID;
import static kr.weathertalk.weather.WeatherStubs.kst;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;

import kr.weathertalk.IntegrationTest;
import kr.weathertalk.notification.RecordingPushSender;

/**
 * 알림 구독 동네 → 활성 동네 → 강수 시작 판정 → 강수 예보 알림 (FR-NBR-10, FR-WEA-09, FR-NOTI-10).
 * 최근 활동 회원이 아무도 없는 동네 7을 비회원 기기 하나만 구독해도 활성 동네가 되어, 수집 직후 강수 시작 판정이 돌고 그 기기로 알림이 간다.
 */
@IntegrationTest
class GuestSubscribedPrecipitationScenarioTests extends FieldReportTestBase {

	@Autowired
	RecordingPushSender pushes;

	@BeforeEach
	void onlyGuestSubscriptionActivatesNeighborhood7() {
		jdbc.sql("DELETE FROM nbr_current_neighborhood WHERE neighborhood_code = :code").param("code", N7.value()).update();
		jdbc.sql("DELETE FROM nbr_interest_neighborhood WHERE neighborhood_code = :code").param("code", N7.value()).update();
		jdbc.sql("DELETE FROM noti_device").update();
		jdbc.sql("DELETE FROM nbr_notification_subscription").update();
		pushes.clear();
	}

	@AfterEach
	void cleanUp() {
		jdbc.sql("DELETE FROM noti_device").update();
		jdbc.sql("DELETE FROM nbr_notification_subscription").update();
		pushes.clear();
	}

	@Test
	void guestOnlySubscribedNeighborhoodGetsPrecipitationStartAlert() throws Exception {
		clock.set(kst("2026-10-13T08:15"));
		assertThat(neighborhoodApi.findActiveNeighborhoods(N7_GRID))
			.doesNotContain(N7);

		String json = mvc.perform(post("/api/v1/notification/devices/confirm").contentType(MediaType.APPLICATION_JSON)
			.content("{\"platform\":\"ANDROID\",\"pushToken\":\"guest-n7\",\"osPermission\":\"GRANTED\"}"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		String deviceId = JsonPath.read(json, "$.deviceId");
		String deviceKey = JsonPath.read(json, "$.deviceKey");
		mvc.perform(put("/api/v1/notification/subscriptions").header("X-Device-Id", deviceId)
			.header("X-Device-Key", deviceKey)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"targetType\":\"FIXED\",\"neighborhoodId\":\"" + N7.value()
					+ "\",\"dailyEnabled\":false,\"precipitationForecastEnabled\":true,\"fieldReportPrecipitationEnabled\":false}"))
			.andExpect(status().isOk());
		assertThat(neighborhoodApi.findActiveNeighborhoods()).contains(N7);
		assertThat(neighborhoodApi.findActiveForecastGrids()).contains(N7_GRID);

		stubs.observation(N7_GRID, kst("2026-10-13T08:00"),
				Map.of("T1H", "15", "REH", "70", "WSD", "1.0", "RN1", "0", "PTY", "0"));
		stubs.shortTerm(N7_GRID, kst("2026-10-13T08:00"), 30, at -> at.getHour() == 10 && at.getDayOfMonth() == 13
				? Map.of("PTY", "1", "POP", "80", "PCP", "2.0mm") : Map.of());
		weatherApi.findCurrentObservation(N7);

		assertThat(events(PrecipitationStartForecasted.class)).singleElement()
			.satisfies(e -> assertThat(e.neighborhoodId()).isEqualTo(N7));
		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(pushes.sentTo("guest-n7")).singleElement()
			.satisfies(m -> {
				assertThat(m.title()).contains("비 소식");
				assertThat(m.body()).startsWith("오전 10시쯤부터");
				assertThat(m.data()).containsEntry("target", "home.hourly").containsEntry("neighborhoodId", N7.value());
			}));
	}

}
