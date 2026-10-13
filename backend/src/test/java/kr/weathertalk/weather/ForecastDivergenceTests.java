package kr.weathertalk.weather;

import static kr.weathertalk.neighborhood.NeighborhoodTestData.N7;
import static kr.weathertalk.weather.WeatherStubs.N7_GRID;
import static kr.weathertalk.weather.WeatherStubs.kst;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import kr.weathertalk.IntegrationTest;
import kr.weathertalk.weather.ForecastDivergence.AxisDivergence;
import kr.weathertalk.weather.internal.fieldreport.FieldReportAggregator;

/**
 * 예보 vs 주민 차이 조회 (FR-WEA-12, BR-WEA-18·19). divergence.min-reports 3명, min-share 60%.
 * 09:15 기준: 실황 09:00, 단기예보 08:00 발표분(09:00 칸이 현재 시 칸).
 */
@IntegrationTest
class ForecastDivergenceTests extends FieldReportTestBase {

	private void stubSources(String pty, String sky, String observationPty) {
		clock.set(kst("2026-10-13T09:15"));
		if (observationPty != null) {
			stubs.observation(N7_GRID, kst("2026-10-13T09:00"),
					Map.of("T1H", "15", "REH", "70", "WSD", "1.0", "RN1", "0", "PTY", observationPty));
		}
		stubs.shortTerm(N7_GRID, kst("2026-10-13T08:00"), 30,
				at -> at.getHour() == 9 ? Map.of("PTY", pty, "SKY", sky, "POP", "70") : Map.of());
	}

	private void reportN7(FieldReportKind... kinds) {
		for (FieldReportKind kind : kinds) {
			reportAtN7(newMember(), kind);
		}
	}

	private AxisDivergence axis(ForecastDivergence result, DivergenceAxis axis) {
		return result.axes().stream().filter(a -> a.axis() == axis).findFirst().orElseThrow();
	}

	/** 주민 다수 '비' vs 실황 강수 없음 → 강수 배지. 근거는 실황과 그 관측 시각. 강수 배지가 있으면 하늘 배지는 내지 않는다. */
	@Test
	void residentsRainAgainstDryObservationShowsPrecipitationBadge() throws Exception {
		stubSources("0", "4", "0");
		reportN7(FieldReportKind.RAIN, FieldReportKind.RAIN, FieldReportKind.RAIN);

		ForecastDivergence result = weatherApi.findForecastDivergence(N7);

		AxisDivergence precipitation = axis(result, DivergenceAxis.PRECIPITATION);
		assertThat(precipitation.badge()).isTrue();
		assertThat(precipitation.basisValue()).isEqualTo("NONE");
		assertThat(precipitation.basisLabel()).isEqualTo("없음");
		assertThat(precipitation.basisSource()).isEqualTo(ValueSource.OBSERVATION);
		assertThat(precipitation.basisTime()).isEqualTo(kst("2026-10-13T09:00"));
		assertThat(precipitation.residentKind()).isEqualTo(FieldReportKind.RAIN);
		assertThat(precipitation.reporterCount()).isEqualTo(3);
		assertThat(precipitation.majorityCount()).isEqualTo(3);
		assertThat(axis(result, DivergenceAxis.SKY).badge()).isFalse();
		assertThat(axis(result, DivergenceAxis.AIR).badge()).isFalse(); // 대기 제보·측정 없음
		assertThat(result.hasBadge()).isTrue();

		// 비회원에게도 공개(BR-WEA-22)
		mvc.perform(get("/api/v1/weather/neighborhoods/" + N7.value() + "/divergence"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.axes[0].axis").value("PRECIPITATION"))
			.andExpect(jsonPath("$.axes[0].badge").value(true));
	}

	/** 실황이 '없음'이면 강수 기준은 현재 시 예보 칸이고, 근거 시각은 그 발표 시각이다. 주민 다수 '맑음' vs 예보 비 → 강수 배지. */
	@Test
	void forecastIsTheBasisWhenObservationIsMissing() {
		stubSources("1", "4", null);
		reportN7(FieldReportKind.CLEAR, FieldReportKind.CLEAR, FieldReportKind.CLEAR);

		AxisDivergence precipitation = axis(weatherApi.findForecastDivergence(N7), DivergenceAxis.PRECIPITATION);

		assertThat(precipitation.badge()).isTrue();
		assertThat(precipitation.basisValue()).isEqualTo("RAIN");
		assertThat(precipitation.basisSource()).isEqualTo(ValueSource.FORECAST);
		assertThat(precipitation.basisTime()).isEqualTo(kst("2026-10-13T08:00"));
	}

	/** BR-WEA-18 경계: 제보자 2명이면 판정 안 함, 3명 중 다수 비율 60% 미만이면 다수 의견 없음. */
	@Test
	void notEnoughReportersOrNoClearMajorityShowsNoBadge() {
		stubSources("0", "1", "0");
		reportN7(FieldReportKind.RAIN, FieldReportKind.RAIN);
		AxisDivergence two = axis(weatherApi.findForecastDivergence(N7), DivergenceAxis.PRECIPITATION);
		assertThat(two.badge()).isFalse();
		assertThat(two.residentKind()).isNull();
		assertThat(two.reporterCount()).isEqualTo(2);

		reportN7(FieldReportKind.CLEAR, FieldReportKind.OVERCAST); // 비 2/4 = 50%
		AxisDivergence split = axis(weatherApi.findForecastDivergence(N7), DivergenceAxis.PRECIPITATION);
		assertThat(split.badge()).isFalse();
		assertThat(split.residentKind()).isNull();

		reportN7(FieldReportKind.RAIN); // 비 3/5 = 60% (경계 포함)
		AxisDivergence majority = axis(weatherApi.findForecastDivergence(N7), DivergenceAxis.PRECIPITATION);
		assertThat(majority.badge()).isTrue();
		assertThat(majority.majorityCount()).isEqualTo(3);
	}

	/** 하늘: 주민 '맑음' vs 예보 '흐림' → 배지, 예보 '구름많음'은 어느 쪽과도 어긋나지 않는다. */
	@Test
	void skyBadgeIgnoresMostlyCloudy() {
		stubSources("0", "4", "0");
		reportN7(FieldReportKind.CLEAR, FieldReportKind.CLEAR, FieldReportKind.CLEAR);
		ForecastDivergence overcast = weatherApi.findForecastDivergence(N7);
		assertThat(axis(overcast, DivergenceAxis.PRECIPITATION).badge()).isFalse();
		AxisDivergence sky = axis(overcast, DivergenceAxis.SKY);
		assertThat(sky.badge()).isTrue();
		assertThat(sky.basisValue()).isEqualTo("OVERCAST");
		assertThat(sky.basisSource()).isEqualTo(ValueSource.FORECAST);
		assertThat(sky.basisTime()).isEqualTo(kst("2026-10-13T08:00"));

		WeatherStubs.resetAll(jdbc, redis, server);
		stubs = new WeatherStubs(server);
		stubSources("0", "3", "0");
		reportN7(FieldReportKind.OVERCAST, FieldReportKind.OVERCAST, FieldReportKind.OVERCAST);
		assertThat(axis(weatherApi.findForecastDivergence(N7), DivergenceAxis.SKY).badge()).isFalse();
	}

	/** 대기: 주민 '미세먼지 나쁨' vs 측정 좋음·보통 → 배지. 근거는 측정 시각. */
	@Test
	void airBadgeComparesCombinedDustGrade() {
		stubSources("0", "1", "0");
		stubs.stationsNearN7("샘플측정소A");
		stubs.measurement("샘플측정소A", new String[] { "2026-10-13 09:00", "30", "10", "1", "2", "1" });
		reportN7(FieldReportKind.DUST_BAD, FieldReportKind.DUST_BAD, FieldReportKind.DUST_BAD);

		AxisDivergence air = axis(weatherApi.findForecastDivergence(N7), DivergenceAxis.AIR);

		assertThat(air.badge()).isTrue();
		assertThat(air.basisValue()).isEqualTo("MODERATE");
		assertThat(air.basisSource()).isEqualTo(ValueSource.MEASUREMENT);
		assertThat(air.basisTime()).isEqualTo(kst("2026-10-13T09:00"));
		assertThat(air.residentKind()).isEqualTo(FieldReportKind.DUST_BAD);
	}

	/** 기준 측 값이 없으면 그 축은 표시 안 함(오류 아님). */
	@Test
	void missingBasisShowsNoBadge() {
		clock.set(kst("2026-10-13T09:15"));
		reportN7(FieldReportKind.RAIN, FieldReportKind.RAIN, FieldReportKind.RAIN);

		ForecastDivergence result = weatherApi.findForecastDivergence(N7);

		assertThat(result.hasBadge()).isFalse();
		assertThat(axis(result, DivergenceAxis.PRECIPITATION).basisValue()).isNull();
		assertThat(axis(result, DivergenceAxis.PRECIPITATION).residentKind()).isEqualTo(FieldReportKind.RAIN);
	}

	/** BR-WEA-18 다수 의견 판정 경계(순수 규칙). */
	@Test
	void majorityRuleBoundaries() {
		assertThat(majority(3, Map.of(FieldReportKind.RAIN, 2, FieldReportKind.CLEAR, 1))).isEqualTo(FieldReportKind.RAIN);
		assertThat(majority(5, Map.of(FieldReportKind.RAIN, 3, FieldReportKind.CLEAR, 2))).isEqualTo(FieldReportKind.RAIN);
		assertThat(majority(5, Map.of(FieldReportKind.RAIN, 2, FieldReportKind.CLEAR, 2, FieldReportKind.OVERCAST, 1)))
			.isNull();
		assertThat(majority(2, Map.of(FieldReportKind.RAIN, 2))).isNull();
		assertThat(majority(0, Map.of())).isNull();
	}

	private static FieldReportKind majority(int reporters, Map<FieldReportKind, Integer> counts) {
		return FieldReportAggregator.majority(new EnumMap<>(counts.isEmpty() ? Map.of(FieldReportKind.RAIN, 0) : counts),
				reporters, 3, 0.6);
	}

}
