package kr.weathertalk.home;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.INSIDE_N1;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N1;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N3;
import static kr.weathertalk.weather.WeatherStubs.kst;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import kr.weathertalk.AuthTestClient;
import kr.weathertalk.IntegrationTest;
import kr.weathertalk.feeling.FeelingTestBase;
import kr.weathertalk.member.TemperatureSensitivity;
import kr.weathertalk.neighborhood.ForecastGrid;
import kr.weathertalk.weather.WeatherStubs;

/**
 * 홈 M3 섹션 (07 §1.2~1.4, SCR-01): 민감도별 체감 3그룹·내 그룹, 피드백 버튼 상태(회원), 옷 추천, 어제와 비교, 외출 시각 기준,
 * 그리고 민감도별 체감이 실패할 때의 의존 섹션 연쇄 처리.
 */
@IntegrationTest
class HomeFeelingSectionsTests extends FeelingTestBase {

	static final Instant NOW = kst("2026-10-13T09:15");

	private static boolean warmedUp;

	/** 첫 조회의 준비 비용(HTTP 클라이언트·JIT)이 섹션 시간 제한을 넘지 않게 한 번 데워 둔다. */
	@BeforeEach
	void warmUp() {
		if (!warmedUp) {
			clock.set(NOW);
			feelingApi.findSensitivityFeelings(N1, null);
			feelingApi.compareWithYesterday(N1, TemperatureSensitivity.NORMAL, null);
			WeatherStubs.resetAll(jdbc, redis, server);
			warmedUp = true;
		}
	}

	/** 현재 23.5℃(보통 = 5 조금 더움, 추위를 많이 탐 = 4 딱 좋음), 13시 예보 12℃(보통 = 2 추움), 18시 예보 비. */
	private void stubWeather() {
		ForecastGrid grid = gridOf(N1);
		stubs.observation(grid, kst("2026-10-13T09:00"), 23.5);
		stubs.shortTerm(grid, kst("2026-10-13T08:00"), 24, at -> switch (at.getHour()) {
			case 13 -> Map.of("TMP", "12");
			case 18 -> Map.of("TMP", "23", "PTY", "1", "PCP", "2.0mm");
			default -> Map.of("TMP", "23");
		});
	}

	/** 비회원·기기 민감도 없음: 강조 없이 '보통' 기준으로 옷 추천·어제 비교, 고르기 안내. 피드백 상태는 조회하지 않는다. */
	@Test
	void guestWithoutDeviceSensitivityGetsNormalBasisAndSelectionHint() throws Exception {
		clock.set(NOW);
		stubWeather();

		mvc.perform(get("/api/v1/home").param("neighborhoodId", N1.value()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.personal.highlightedGroup").doesNotExist())
			.andExpect(jsonPath("$.personal.basisGroup").value("NORMAL"))
			.andExpect(jsonPath("$.personal.selectionSuggested").value(true))
			.andExpect(jsonPath("$.sensitivityFeelings.status").value("OK"))
			.andExpect(jsonPath("$.sensitivityFeelings.data.groups.length()").value(3))
			.andExpect(jsonPath("$.sensitivityFeelings.data.groups[1].temperatureSensitivity").value("NORMAL"))
			.andExpect(jsonPath("$.sensitivityFeelings.data.groups[1].temperatureTag").value("SLIGHTLY_HOT"))
			.andExpect(jsonPath("$.sensitivityFeelings.data.groups[1].source").value("REFERENCE_TABLE"))
			.andExpect(jsonPath("$.sensitivityFeelings.data.groups[1].weatherBasedTemperatureTag").value("SLIGHTLY_HOT"))
			.andExpect(jsonPath("$.outfit.status").value("OK"))
			.andExpect(jsonPath("$.outfit.data.temperatureLevel").value(5))
			.andExpect(jsonPath("$.outfit.data.precipitation").value("NONE"))
			.andExpect(jsonPath("$.yesterdayComparison.status").value("EMPTY"))
			.andExpect(jsonPath("$.yesterdayComparison.data.comparable").value(false))
			.andExpect(jsonPath("$.feedbackStatus").doesNotExist())
			.andExpect(jsonPath("$.departureOutfit").doesNotExist());
	}

	/** 비회원이 기기에서 고른 민감도를 강조·옷 추천 기준으로 쓴다(Q6). */
	@Test
	void guestDeviceSensitivityIsTheBasis() throws Exception {
		clock.set(NOW);
		stubWeather();

		mvc.perform(get("/api/v1/home").param("neighborhoodId", N1.value())
			.param("temperatureSensitivity", "COLD_SENSITIVE"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.personal.highlightedGroup").value("COLD_SENSITIVE"))
			.andExpect(jsonPath("$.personal.selectionSuggested").value(false))
			.andExpect(jsonPath("$.outfit.data.temperatureLevel").value(4));
	}

	/**
	 * 회원: 회원 민감도가 기준(기기 값 무시). 피드백 버튼은 현재 동네에서만 가능하고, 간격 안 기록이 있으면 함께 준다.
	 * 동네 체감이 쌓이면 그 그룹 카드의 산출 출처가 동네 체감으로 바뀌고 옷 추천도 따라간다.
	 */
	@Test
	void memberSeesOwnGroupFeedbackStatusAndNeighborhoodFeeling() throws Exception {
		clock.set(NOW);
		stubWeather();
		AuthTestClient.Session session = signup(TemperatureSensitivity.HEAT_SENSITIVE);
		neighborhoodApi.confirmCurrentNeighborhood(session.memberId(), INSIDE_N1).orElseThrow();

		mvc.perform(get("/api/v1/home").param("temperatureSensitivity", "COLD_SENSITIVE")
			.header(HttpHeaders.AUTHORIZATION, session.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.neighborhood.current").value(true))
			.andExpect(jsonPath("$.personal.highlightedGroup").value("HEAT_SENSITIVE"))
			.andExpect(jsonPath("$.personal.basisGroup").value("HEAT_SENSITIVE"))
			.andExpect(jsonPath("$.feedbackStatus.status").value("OK"))
			.andExpect(jsonPath("$.feedbackStatus.data.available").value(true))
			.andExpect(jsonPath("$.feedbackStatus.data.recentRecord").doesNotExist());

		record(session, "COLD").andExpect(status().isOk());
		for (int i = 0; i < 4; i++) {
			insertRecord(N1, TemperatureSensitivity.HEAT_SENSITIVE, NOW.minusSeconds(60 + i), 2);
		}
		mvc.perform(get("/api/v1/home").header(HttpHeaders.AUTHORIZATION, session.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.feedbackStatus.data.recentRecord.temperatureTag").value("COLD"))
			.andExpect(jsonPath("$.feedbackStatus.data.recentRecord.overwritableUntil").value("2026-10-13T01:15:00Z"))
			.andExpect(jsonPath("$.sensitivityFeelings.data.groups[0].source").value("NEIGHBORHOOD_FEELING"))
			.andExpect(jsonPath("$.sensitivityFeelings.data.groups[0].temperatureTag").value("COLD"))
			.andExpect(jsonPath("$.outfit.data.temperatureLevel").value(2));

		// 관심 동네 등 다른 동네를 볼 때는 피드백 불가(최근 기록도 조회하지 않는다)
		mvc.perform(get("/api/v1/home").param("neighborhoodId", N3.value())
			.header(HttpHeaders.AUTHORIZATION, session.bearer()))
			.andExpect(jsonPath("$.feedbackStatus.data.available").value(false))
			.andExpect(jsonPath("$.feedbackStatus.data.recentRecord").doesNotExist());
	}

	/** 외출 시각은 notification에 기기 단위로 보관한다(FR-NOTI-17). 기기를 등록하고 외출 시각을 저장해 기기 ID·키를 돌려준다. */
	private String[] deviceWithDepartureTimes(String times) throws Exception {
		String json = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
			.post("/api/v1/notification/devices/confirm")
			.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
			.content("{\"platform\":\"IOS\"}")).andReturn().getResponse().getContentAsString();
		String id = com.jayway.jsonpath.JsonPath.read(json, "$.deviceId");
		String key = com.jayway.jsonpath.JsonPath.read(json, "$.deviceKey");
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
			.put("/api/v1/notification/devices/me/departure-times")
			.header("X-Device-Id", id)
			.header("X-Device-Key", key)
			.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
			.content("{\"departureTimes\":[" + times + "]}")).andExpect(status().isOk());
		return new String[] { id, key };
	}

	/** 외출 시각 기준: 시각별 기준 그룹 체감 → 가장 추운 시각 옷차림 + 일교차 안내, 어느 시각이든 비면 강수 보정. */
	@Test
	void departureTimesGetColdestOutfitWithLayeringAndRain() throws Exception {
		clock.set(NOW);
		stubWeather();

		String[] device = deviceWithDepartureTimes("\"13:00\",\"18:30\"");
		mvc.perform(get("/api/v1/home").param("neighborhoodId", N1.value())
			.header("X-Device-Id", device[0])
			.header("X-Device-Key", device[1]))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.departureOutfit.status").value("OK"))
			.andExpect(jsonPath("$.departureOutfit.data.departures.length()").value(2))
			.andExpect(jsonPath("$.departureOutfit.data.departures[0].feeling.temperatureTag").value("COLD"))
			.andExpect(jsonPath("$.departureOutfit.data.departures[0].feeling.timeKind").value("FUTURE"))
			.andExpect(jsonPath("$.departureOutfit.data.departures[1].feeling.temperatureTag").value("SLIGHTLY_HOT"))
			.andExpect(jsonPath("$.departureOutfit.data.recommendation.basisTemperatureLevel").value(2))
			.andExpect(jsonPath("$.departureOutfit.data.recommendation.basisDepartureAt").value("2026-10-13T04:00:00Z"))
			.andExpect(jsonPath("$.departureOutfit.data.recommendation.layeringAdvised").value(true))
			.andExpect(jsonPath("$.departureOutfit.data.recommendation.recommendation.precipitation").value("RAIN"));
	}

	/** 07 §1.4-3: 민감도별 체감이 실패하면 옷 추천·어제 비교·외출 시각 기준은 조회하지 않고 '실패(선행 섹션)'. 다른 섹션은 그대로. */
	@Test
	void dependentSectionsFailWhenSensitivityFeelingsFail() throws Exception {
		clock.set(NOW);
		ForecastGrid grid = gridOf(N1);
		stubs.shortTerm(grid, kst("2026-10-13T08:00"), 24);
		// 현재 관측 경로(실황·측정소·자외선)를 느리게: 현재 날씨와 민감도별 체감 섹션이 시간 제한(2초)을 넘긴다
		stubs.stationsNearN7("샘플측정소A", "샘플측정소B");
		server.stubFor(WeatherStubs.ncstRequest(grid, kst("2026-10-13T09:00"))
			.willReturn(okJson(WeatherStubs.kmaNoData()).withFixedDelay(2000)));
		server.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathEqualTo(WeatherStubs.REALTIME))
			.willReturn(okJson("{}").withFixedDelay(2000)));
		server.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathEqualTo(WeatherStubs.UV))
			.willReturn(okJson("{}").withFixedDelay(2000)));

		String[] device = deviceWithDepartureTimes("\"18:00\"");
		mvc.perform(get("/api/v1/home").param("neighborhoodId", N1.value())
			.header("X-Device-Id", device[0])
			.header("X-Device-Key", device[1]))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.sensitivityFeelings.status").value("FAILED"))
			.andExpect(jsonPath("$.sensitivityFeelings.failure").value("TIMEOUT"))
			.andExpect(jsonPath("$.outfit.status").value("FAILED"))
			.andExpect(jsonPath("$.outfit.failure").value("PREREQUISITE_FAILED"))
			.andExpect(jsonPath("$.yesterdayComparison.failure").value("PREREQUISITE_FAILED"))
			.andExpect(jsonPath("$.departureOutfit.failure").value("PREREQUISITE_FAILED"))
			.andExpect(jsonPath("$.hourlyForecast.status").value("OK"));

		// 시간 초과로 잘린 조회가 끝날 때까지 기다려 다음 테스트의 모의 서버 기록에 섞이지 않게 한다
		Thread.sleep(Duration.ofSeconds(6).toMillis());
	}

}
