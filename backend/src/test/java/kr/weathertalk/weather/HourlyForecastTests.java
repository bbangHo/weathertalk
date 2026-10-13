package kr.weathertalk.weather;

import static kr.weathertalk.neighborhood.NeighborhoodTestData.N7;
import static kr.weathertalk.weather.WeatherStubs.N7_GRID;
import static kr.weathertalk.weather.WeatherStubs.kst;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import kr.weathertalk.IntegrationTest;
import kr.weathertalk.weather.internal.source.SourceData;
import kr.weathertalk.weather.internal.store.ShortTermStore;

/**
 * 시간별 예보 조회 (FR-WEA-02), 단기예보 저장 (03 §3.4).
 */
@IntegrationTest
class HourlyForecastTests extends WeatherTestBase {

	@Autowired
	ShortTermStore shortTermStore;

	/** legacy 04-#23·07-#20: 칸은 날짜+시각으로 식별한다. 24시간을 넘는 응답에서 다른 날 같은 시각이 섞이거나 지워지지 않는다. */
	@Test
	void slotsAreKeyedByDateAndHourAcrossMoreThan24Hours_04_23() {
		clock.set(kst("2026-10-13T09:15"));
		stubs.shortTerm(N7_GRID, kst("2026-10-13T08:00"), 63,
				at -> Map.of("TMP", String.valueOf(at.getDayOfMonth() + at.getHour() / 100.0)));

		HourlyForecast forecast = weatherApi.findHourlyForecast(N7, 48);

		assertThat(forecast.issuedAt()).isEqualTo(kst("2026-10-13T08:00"));
		assertThat(forecast.freshness()).isEqualTo(Freshness.FRESH);
		assertThat(forecast.slots()).hasSize(48);
		assertThat(forecast.slots().get(0).forecastAt()).isEqualTo(kst("2026-10-13T09:00"));
		assertThat(forecast.slots()).filteredOn(s -> s.hour() == 9)
			.extracting(HourlyForecastSlot::date, HourlyForecastSlot::temperature)
			.containsExactly(tuple(LocalDate.of(2026, 10, 13), 13.09), tuple(LocalDate.of(2026, 10, 14), 14.09));
		assertThat(forecast.slots()).extracting(HourlyForecastSlot::forecastAt).isSorted().doesNotHaveDuplicates();
	}

	/** FR-WEA-02 2단계: 지난 칸은 돌려주지 않는다. 수집 실패 시 마지막 정상 발표분의 남은 칸을 '지연'으로. */
	@Test
	void excludesPastSlotsAndServesLastIssueAsDelayedOnFailure() {
		clock.set(kst("2026-10-13T09:15"));
		stubs.shortTerm(N7_GRID, kst("2026-10-13T08:00"), 63);
		weatherApi.findHourlyForecast(N7, null);

		stubs.shortTermResponse(N7_GRID, kst("2026-10-13T11:00"), WeatherStubs.serverError());
		clock.set(kst("2026-10-13T11:30"));
		HourlyForecast forecast = weatherApi.findHourlyForecast(N7, null);

		assertThat(forecast.freshness()).isEqualTo(Freshness.DELAYED);
		assertThat(forecast.issuedAt()).isEqualTo(kst("2026-10-13T08:00"));
		assertThat(forecast.slots()).hasSize(24); // weather.short-term.default-hours
		assertThat(forecast.slots().get(0).forecastAt()).isEqualTo(kst("2026-10-13T11:00"));
	}

	/** BR-WEA-03: max-staleness(6시간)를 넘긴 발표분은 '없음' — 빈 목록이며 서버 오류로 끝내지 않는다. */
	@Test
	void issueOlderThanMaxStalenessIsNone() {
		clock.set(kst("2026-10-13T09:15"));
		stubs.shortTerm(N7_GRID, kst("2026-10-13T08:00"), 63);
		weatherApi.findHourlyForecast(N7, null);

		clock.set(kst("2026-10-13T14:30"));
		HourlyForecast forecast = weatherApi.findHourlyForecast(N7, null);

		assertThat(forecast.freshness()).isEqualTo(Freshness.NONE);
		assertThat(forecast.slots()).isEmpty();
		assertThat(forecast.nextPrecipitation()).isNull();
	}

	/**
	 * legacy 04-#17, BR-WEA-14: 다음 강수는 강수 형태가 있는 가장 이른 칸(강수확률만 높은 칸 제외).
	 * legacy 04 §3 본문: 범주형 강수량은 범주 그대로("1mm 미만") 표시한다.
	 */
	@Test
	void nextPrecipitationIsEarliestSlotWithPrecipitationType_04_17() {
		clock.set(kst("2026-10-13T09:15"));
		stubs.shortTerm(N7_GRID, kst("2026-10-13T08:00"), 63, at -> {
			if (at.getDayOfMonth() == 13 && at.getHour() == 12) {
				return Map.of("POP", "90", "PTY", "0");
			}
			if (at.getDayOfMonth() == 13 && at.getHour() == 14) {
				return Map.of("POP", "60", "PTY", "1", "PCP", "1.0mm 미만");
			}
			if (at.getDayOfMonth() == 13 && at.getHour() == 18) {
				return Map.of("POP", "70", "PTY", "3", "SNO", "5.0cm 이상");
			}
			return Map.of();
		});

		HourlyForecast forecast = weatherApi.findHourlyForecast(N7, null);

		assertThat(forecast.nextPrecipitation().startsAt()).isEqualTo(kst("2026-10-13T14:00"));
		assertThat(forecast.nextPrecipitation().type()).isEqualTo(PrecipitationType.RAIN);
		assertThat(forecast.nextPrecipitation().phrase()).isEqualTo("약한 비");
		HourlyForecastSlot rain = forecast.slots().stream().filter(s -> s.hour() == 14).findFirst().orElseThrow();
		assertThat(rain.precipitation().label()).isEqualTo("1mm 미만");
		assertThat(rain.precipitation().upper()).isEqualTo(1.0);
		assertThat(rain.precipitation().lowerExclusive()).isTrue();
		HourlyForecastSlot snow = forecast.slots().stream().filter(s -> s.hour() == 18).findFirst().orElseThrow();
		assertThat(snow.precipitationPhrase()).isEqualTo("강한 눈");
		assertThat(snow.snowfall().label()).isEqualTo("5cm 이상");
		assertThat(snow.dayNight()).isEqualTo(DayNight.NIGHT);
	}

	/**
	 * legacy 04-#2·04-#19: 같은 발표분을 다시 받으면 모든 항목을 통째로 교체한다(기온도 바뀜). 다시 저장해도 키 충돌이 없다.
	 */
	@Test
	void recollectingSameIssueReplacesWholeIssueIdempotently_04_2_04_19() {
		Instant issued = kst("2026-10-13T08:00");
		Instant at = kst("2026-10-13T09:00");
		shortTermStore.replace(N7_GRID, issued, List.of(slot(at, 15.0, 30), slot(at.plusSeconds(3600), 16.0, 30)),
				issued);
		shortTermStore.replace(N7_GRID, issued, List.of(slot(at, 17.0, 80)), issued);

		ShortTermStore.Issue stored = shortTermStore.findLatestIssuedAtOrBefore(N7_GRID, at).orElseThrow();
		assertThat(stored.slots()).singleElement().satisfies(s -> {
			assertThat(s.temperature()).isEqualTo(17.0);
			assertThat(s.precipitationProbability()).isEqualTo(80);
		});
	}

	private static SourceData.ShortTermSlot slot(Instant at, double temperature, int pop) {
		return new SourceData.ShortTermSlot(at, temperature, 50.0, 1.0, pop, PrecipitationType.NONE,
				PrecipitationAmount.none("mm"), PrecipitationAmount.none("cm"), SkyCondition.CLEAR, null, null);
	}

}
