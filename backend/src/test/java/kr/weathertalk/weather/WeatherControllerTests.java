package kr.weathertalk.weather;

import static kr.weathertalk.weather.WeatherStubs.N7_GRID;
import static kr.weathertalk.weather.WeatherStubs.kst;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import kr.weathertalk.IntegrationTest;

/**
 * 날씨 웹 API: 비회원 공개 (BR-WEA-22). 외부 장애는 오류가 아니라 신선도로 드러난다.
 */
@IntegrationTest
class WeatherControllerTests extends WeatherTestBase {

	@Autowired
	MockMvc mvc;

	@Test
	void guestCanReadCurrentHourlyAndDailyWeather() throws Exception {
		clock.set(kst("2026-10-13T09:15"));
		stubs.observation(N7_GRID, kst("2026-10-13T09:00"), 18.0);
		stubs.shortTerm(N7_GRID, kst("2026-10-13T08:00"), 63);

		mvc.perform(get("/api/v1/weather/neighborhoods/9900000007/current"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.temperature").value(18.0))
			.andExpect(jsonPath("$.weatherFreshness").value("FRESH"))
			.andExpect(jsonPath("$.sources.TEMPERATURE").value("OBSERVATION"))
			.andExpect(jsonPath("$.dayNight").value("DAY"))
			.andExpect(jsonPath("$.airQuality.freshness").value("NONE")); // 에어코리아 미응답 → 없음(오류 아님)
		mvc.perform(get("/api/v1/weather/neighborhoods/9900000007/hourly").param("hours", "3"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.slots.length()").value(3))
			.andExpect(jsonPath("$.slots[0].date").value("2026-10-13"))
			.andExpect(jsonPath("$.slots[0].hour").value(9));
		mvc.perform(get("/api/v1/weather/neighborhoods/9900000007/daily"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.days.length()").value(10));
	}

	@Test
	void unknownNeighborhoodIsNotFoundAndBadRangeIsInvalid() throws Exception {
		mvc.perform(get("/api/v1/weather/neighborhoods/0000000000/current"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		mvc.perform(get("/api/v1/weather/neighborhoods/9900000007/hourly").param("hours", "0"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}

}
