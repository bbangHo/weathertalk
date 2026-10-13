package kr.weathertalk.weather;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N7;
import static kr.weathertalk.weather.WeatherStubs.N7_GRID;
import static kr.weathertalk.weather.WeatherStubs.kst;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import kr.weathertalk.IntegrationTest;

/**
 * 중기 예보 조회 — 일별 예보 (FR-WEA-03, 03 §3.5).
 */
@IntegrationTest
class DailyForecastTests extends WeatherTestBase {

	private void stubShortTerm() {
		stubs.shortTerm(N7_GRID, kst("2026-10-13T08:00"), 63, at -> {
			if (at.getDayOfMonth() == 14 && at.getHour() == 15) {
				return Map.of("TMX", "21.5");
			}
			if (at.getDayOfMonth() == 14 && at.getHour() == 6) {
				return Map.of("TMN", "3.0");
			}
			return Map.of();
		});
	}

	private void stubMidTerm() {
		Map<String, Object> land = new LinkedHashMap<>();
		for (int n = 4; n <= 7; n++) {
			land.put("wf" + n + "Am", "구름많고 비");
			land.put("wf" + n + "Pm", "흐리고 비/눈");
			land.put("rnSt" + n + "Am", 60);
			land.put("rnSt" + n + "Pm", 70);
		}
		for (int n = 8; n <= 10; n++) {
			land.put("wf" + n, "흐림");
			land.put("rnSt" + n, 30);
		}
		stubs.midLand(MID_LAND_REGION, kst("2026-10-13T06:00"), land);
		Map<String, Object> temperature = new LinkedHashMap<>();
		for (int n = 4; n <= 10; n++) {
			temperature.put("taMin" + n, 5 + n);
			temperature.put("taMax" + n, 15 + n);
		}
		stubs.midTemperature(MID_TEMPERATURE_REGION, kst("2026-10-13T06:00"), temperature);
	}

	/**
	 * 단기예보가 그 날 남은 시간을 덮는 날은 단기로, 아니면 중기로 만든다. 어느 쪽도 없는 날은 비워 둔다.
	 * legacy 04-#27: 최고·최저는 하나의 정의(TMX·TMN 우선)만 쓴다.
	 */
	@Test
	void buildsShortTermDaysThenMidTermDaysAndLeavesUncoveredDaysEmpty_04_27() {
		clock.set(kst("2026-10-13T09:15"));
		stubShortTerm();
		stubMidTerm();

		DailyForecast forecast = weatherApi.findDailyForecast(N7);

		assertThat(forecast.freshness()).isEqualTo(Freshness.FRESH);
		assertThat(forecast.days()).hasSize(10).first().extracting(DailyForecastDay::date)
			.isEqualTo(LocalDate.of(2026, 10, 13));
		assertThat(forecast.days()).extracting(DailyForecastDay::source)
			.containsExactly(DailyForecastSource.SHORT_TERM, DailyForecastSource.SHORT_TERM,
					DailyForecastSource.SHORT_TERM, null, DailyForecastSource.MID_TERM, DailyForecastSource.MID_TERM,
					DailyForecastSource.MID_TERM, DailyForecastSource.MID_TERM, DailyForecastSource.MID_TERM,
					DailyForecastSource.MID_TERM);

		DailyForecastDay tomorrow = forecast.days().get(1);
		assertThat(tomorrow.maxTemperature()).isEqualTo(21.5); // TMX(칸 기온 최대 19보다 우선)
		assertThat(tomorrow.minTemperature()).isEqualTo(3.0); // TMN
		assertThat(tomorrow.amSky()).isEqualTo(SkyCondition.CLEAR);
		assertThat(tomorrow.issuedAt()).isEqualTo(kst("2026-10-13T08:00"));

		assertThat(forecast.days().get(3).isEmpty()).isTrue();

		DailyForecastDay day4 = forecast.days().get(4); // 10-17 = 발표일 + 4
		assertThat(day4.date()).isEqualTo(LocalDate.of(2026, 10, 17));
		assertThat(day4.amSky()).isEqualTo(SkyCondition.MOSTLY_CLOUDY);
		assertThat(day4.amPrecipitationType()).isEqualTo(PrecipitationType.RAIN);
		assertThat(day4.pmSky()).isEqualTo(SkyCondition.OVERCAST);
		assertThat(day4.pmPrecipitationType()).isEqualTo(PrecipitationType.RAIN_SNOW);
		assertThat(day4.amPrecipitationProbability()).isEqualTo(60);
		assertThat(day4.minTemperature()).isEqualTo(9.0);
		assertThat(day4.maxTemperature()).isEqualTo(19.0);
		assertThat(day4.issuedAt()).isEqualTo(kst("2026-10-13T06:00"));

		DailyForecastDay day8 = forecast.days().get(8); // 오전·오후 구분 없는 날
		assertThat(day8.amSky()).isEqualTo(SkyCondition.OVERCAST);
		assertThat(day8.pmSky()).isEqualTo(SkyCondition.OVERCAST);
		assertThat(day8.pmPrecipitationProbability()).isEqualTo(30);
	}

	/** FR-WEA-03 실패 케이스: 중기 부분이 '없음'이면 단기로 만든 날만 돌려준다. */
	@Test
	void midTermNoneReturnsOnlyShortTermDays() {
		clock.set(kst("2026-10-13T09:15"));
		stubShortTerm();

		DailyForecast forecast = weatherApi.findDailyForecast(N7);

		assertThat(forecast.freshness()).isEqualTo(Freshness.FRESH);
		assertThat(forecast.days()).filteredOn(d -> !d.isEmpty()).hasSize(3)
			.allMatch(d -> d.source() == DailyForecastSource.SHORT_TERM);
	}

	/** FR-WEA-03: 중기는 정기 수집분을 쓰고, 저장분이 '없음'일 때만 요청 시 수집한다(직전 발표분이면 '지연'으로 그대로 쓴다). */
	@Test
	void midTermIsCollectedOnRequestOnlyWhenNone() {
		clock.set(kst("2026-10-13T09:15"));
		stubShortTerm();
		stubMidTerm();
		weatherApi.findDailyForecast(N7);

		clock.set(kst("2026-10-13T18:45"));
		DailyForecast forecast = weatherApi.findDailyForecast(N7);

		server.verify(exactly(0),
				getRequestedFor(urlPathEqualTo(WeatherStubs.MID_LAND)).withQueryParam("tmFc", equalTo("202610131800")));
		assertThat(forecast.days()).filteredOn(d -> d.source() == DailyForecastSource.MID_TERM).isNotEmpty();
		assertThat(forecast.freshness()).isEqualTo(Freshness.DELAYED);
	}

}
