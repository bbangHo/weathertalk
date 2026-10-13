package kr.weathertalk.weather.internal.conversion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Month;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import kr.weathertalk.weather.AirGrade;
import kr.weathertalk.weather.DayNight;
import kr.weathertalk.weather.PrecipitationAmount;
import kr.weathertalk.weather.PrecipitationType;
import kr.weathertalk.weather.SkyCondition;
import kr.weathertalk.weather.UvGrade;
import kr.weathertalk.weather.WeatherPropertiesFixture;
import kr.weathertalk.weather.internal.config.WeatherProperties;

/**
 * 값 변환표 (BR-WEA-09~13, 23, 03 §3.6). 경계값은 모두 설정 초기값(application.yml) 기준.
 */
class ConversionTableTests {

	final WeatherProperties properties = WeatherPropertiesFixture.defaults();

	final RainPhraseCalculator rainPhrase = new RainPhraseCalculator(properties.rainPhrase());

	final FeelsLikeCalculator feelsLike = new FeelsLikeCalculator(properties.feelsLike());

	final DayNightJudge dayNight = new DayNightJudge(properties.dayNight());

	/** legacy 04-#5: 1 맑음, 3 구름많음, 4 흐림. 그 외(2, 결측)는 맑음이 아니라 없음. */
	@ParameterizedTest(name = "SKY {0} → {1}")
	@CsvSource(nullValues = "null", value = { "1, CLEAR", "3, MOSTLY_CLOUDY", "4, OVERCAST", "2, null", "0, null",
			"-999, null", "'', null" })
	void skyCode_04_5(String code, SkyCondition expected) {
		assertThat(KmaCodes.sky(code)).isEqualTo(expected);
	}

	@ParameterizedTest(name = "단기 PTY {0} → {1}")
	@CsvSource(nullValues = "null", value = { "0, NONE", "1, RAIN", "2, RAIN_SNOW", "3, SNOW", "4, SHOWER",
			"5, null", "7, null" })
	void shortTermPrecipitationType(String code, PrecipitationType expected) {
		assertThat(KmaCodes.shortTermPrecipitationType(code)).isEqualTo(expected);
	}

	@ParameterizedTest(name = "실황 PTY {0} → {1}")
	@CsvSource(nullValues = "null", value = { "0, NONE", "1, RAIN", "2, RAIN_SNOW", "3, SNOW", "5, RAIN",
			"6, RAIN_SNOW", "7, SNOW", "4, null", "9, null" })
	void observationPrecipitationType(String code, PrecipitationType expected) {
		assertThat(KmaCodes.observationPrecipitationType(code)).isEqualTo(expected);
	}

	/** BR-WEA-11, legacy 04 §3 본문: "1.0mm 미만"은 1.0으로 바꾸지 않고 구간으로 둔다. */
	@ParameterizedTest(name = "{0} → {1}")
	@CsvSource(delimiter = '|', nullValues = "null", value = { "강수없음|없음|0.0|0.0|false", "1.0mm 미만|1mm 미만|0.0|1.0|true",
			"6.2mm|6.2mm|6.2|6.2|false", "30.0~50.0mm|30~50mm|30.0|50.0|false", "50.0mm 이상|50mm 이상|50.0|null|false",
			"0|없음|0.0|0.0|false" })
	void precipitationAmountRange(String raw, String label, double lower, Double upper, boolean exclusive) {
		PrecipitationAmount amount = KmaCodes.amount(raw, "mm");
		assertThat(amount.label()).isEqualTo(label);
		assertThat(amount.lower()).isEqualTo(lower);
		assertThat(amount.upper()).isEqualTo(upper);
		assertThat(amount.lowerExclusive()).isEqualTo(exclusive);
	}

	@Test
	void snowfallAndMissingAmounts() {
		assertThat(KmaCodes.amount("적설없음", "cm").isPositive()).isFalse();
		assertThat(KmaCodes.amount("1.0cm 미만", "cm").label()).isEqualTo("1cm 미만");
		assertThat(KmaCodes.amount("1.0cm 미만", "cm").isPositive()).isTrue();
		assertThat(KmaCodes.amount("5.0cm 이상", "cm").label()).isEqualTo("5cm 이상");
		assertThat(KmaCodes.amount("-", "mm")).isNull();
		assertThat(KmaCodes.amount("-998.9", "mm")).isNull();
		assertThat(KmaCodes.amount(null, "mm")).isNull();
	}

	/** BR-WEA-13: 기상청 강도 구분(비 3/15/30mm, 눈 1/5cm), 경계는 '이상', 형태가 있으면 최소 '약한'. */
	@ParameterizedTest(name = "{0} {1} → {3}")
	@CsvSource(delimiter = '|', nullValues = "null", value = { "NONE|강수없음|null|강수 없음",
			"RAIN|1.0mm 미만|null|약한 비", "RAIN|2.9mm|null|약한 비", "RAIN|3.0mm|null|보통 비", "RAIN|14.9mm|null|보통 비",
			"RAIN|15.0mm|null|강한 비", "RAIN|29.9mm|null|강한 비", "RAIN|30.0~50.0mm|null|매우 강한 비",
			"RAIN|50.0mm 이상|null|매우 강한 비", "RAIN|강수없음|null|약한 비", "RAIN|null|null|약한 비",
			"RAIN_SNOW|15.0mm|null|강한 비·눈", "SNOW|null|0.9cm|약한 눈", "SNOW|null|1.0cm|보통 눈", "SNOW|null|4.9cm|보통 눈",
			"SNOW|null|5.0cm 이상|강한 눈", "SNOW|null|null|약한 눈", "SHOWER|30.0~50.0mm|null|소나기" })
	void rainPhraseTable(PrecipitationType type, String rain, String snow, String expected) {
		assertThat(rainPhrase.phrase(type, KmaCodes.amount(rain, "mm"), KmaCodes.amount(snow, "cm")))
			.isEqualTo(expected);
	}

	@Test
	void unknownPrecipitationTypeHasNoPhrase() {
		assertThat(rainPhrase.phrase(null, KmaCodes.amount("6.0mm", "mm"), null)).isNull();
	}

	/** legacy 04-#15, BR-WEA-12: 겨울 공식은 기온 ≤ 10℃ 그리고 풍속 '이상' 1.3m/s, 여름 공식은 5~9월·25℃ 이상. */
	@Test
	void feelsLikeFormulaAndApplicationConditions_04_15() {
		// 겨울 공식: -5℃, 5m/s(18km/h) → 약 -11.2℃
		assertThat(feelsLike.calculate(-5.0, 50.0, 5.0, Month.JANUARY)).hasValueSatisfying(
				v -> assertThat(v).isCloseTo(-11.19, within(0.05)));
		// 경계: 10℃·1.3m/s는 적용(이하·이상), 10.1℃ 또는 1.29m/s는 미적용
		assertThat(feelsLike.calculate(10.0, 50.0, 1.3, Month.MARCH).orElseThrow()).isNotEqualTo(10.0);
		assertThat(feelsLike.calculate(10.1, 50.0, 1.3, Month.MARCH)).contains(10.1);
		assertThat(feelsLike.calculate(10.0, 50.0, 1.29, Month.MARCH)).contains(10.0);
		// 여름 공식: 7월 30℃ 70% → 공식값(기온과 다름), 10월 30℃는 적용 월이 아니므로 기온 그대로
		assertThat(feelsLike.calculate(30.0, 70.0, 1.0, Month.JULY).orElseThrow()).isCloseTo(31.3, within(0.2));
		assertThat(feelsLike.calculate(30.0, 70.0, 1.0, Month.OCTOBER)).contains(30.0);
		// 여름 기온 하한 25℃ 경계
		assertThat(feelsLike.calculate(24.9, 70.0, 1.0, Month.JULY)).contains(24.9);
		assertThat(feelsLike.calculate(25.0, 70.0, 1.0, Month.JULY).orElseThrow()).isNotEqualTo(25.0);
	}

	@Test
	void feelsLikeIsNoneWhenRequiredInputIsMissing() {
		assertThat(feelsLike.calculate(null, 50.0, 2.0, Month.JANUARY)).isEmpty();
		assertThat(feelsLike.calculate(5.0, 50.0, null, Month.JANUARY)).isEmpty(); // 겨울 판정에 풍속 필요
		assertThat(feelsLike.calculate(30.0, null, 1.0, Month.JULY)).isEmpty(); // 여름 판정에 습도 필요
		assertThat(feelsLike.calculate(18.0, null, null, Month.OCTOBER)).contains(18.0); // 어느 공식도 아님
	}

	/** legacy 04-#14: 기상청 공식 5단계(낮음 0–2, 보통 3–5, 높음 6–7, 매우높음 8–10, 위험 11+), 값 없으면 '정보 없음'. */
	@ParameterizedTest(name = "UV {0} → {1}")
	@CsvSource(nullValues = "null", value = { "0, LOW", "2, LOW", "3, MODERATE", "5, MODERATE", "6, HIGH", "7, HIGH",
			"8, VERY_HIGH", "10, VERY_HIGH", "11, EXTREME", "15, EXTREME", "null, null" })
	void uvGradeBoundaries_04_14(Integer index, UvGrade expected) {
		assertThat(UvGrade.of(index)).isEqualTo(expected);
	}

	@Test
	void uvLabelsAreOfficialAndNotAirQualityWords_04_14() {
		assertThat(List.of(UvGrade.values())).extracting(UvGrade::label)
			.containsExactly("낮음", "보통", "높음", "매우높음", "위험")
			.doesNotContain("좋음", "나쁨");
		assertThat(kr.weathertalk.weather.UvIndex.of(null, null, kr.weathertalk.weather.Freshness.NONE).gradeLabel())
			.isEqualTo("정보 없음");
	}

	/** legacy 03-#15: PM10·PM2.5는 각자의 공식 경계를 쓴다(PM2.5에 PM10 경계를 쓰지 않는다). */
	@ParameterizedTest(name = "PM10 {0} → {1}")
	@CsvSource({ "0, GOOD", "30, GOOD", "31, MODERATE", "80, MODERATE", "81, BAD", "150, BAD", "151, VERY_BAD" })
	void pm10Boundaries_03_15(double value, AirGrade expected) {
		assertThat(AirGrades.pm10(value)).isEqualTo(expected);
	}

	@ParameterizedTest(name = "PM2.5 {0} → {1}")
	@CsvSource({ "0, GOOD", "15, GOOD", "16, MODERATE", "35, MODERATE", "36, BAD", "75, BAD", "76, VERY_BAD" })
	void pm25Boundaries_03_15(double value, AirGrade expected) {
		assertThat(AirGrades.pm25(value)).isEqualTo(expected);
	}

	@Test
	void combinedDustGradeIsTheWorseOfTwo() {
		assertThat(AirGrade.worse(AirGrade.GOOD, AirGrade.BAD)).isEqualTo(AirGrade.BAD);
		assertThat(AirGrade.worse(null, AirGrade.MODERATE)).isEqualTo(AirGrade.MODERATE);
		assertThat(AirGrades.fromCode(4)).isEqualTo(AirGrade.VERY_BAD);
		assertThat(AirGrades.fromCode(0)).isNull();
	}

	/** BR-WEA-23: 06:00 이상 18:00 미만이면 낮(Asia/Seoul). */
	@ParameterizedTest(name = "{0} → {1}")
	@CsvSource({ "2026-10-13T05:59, NIGHT", "2026-10-13T06:00, DAY", "2026-10-13T17:59, DAY",
			"2026-10-13T18:00, NIGHT", "2026-10-13T00:00, NIGHT" })
	void dayNight(String localTime, DayNight expected) {
		assertThat(dayNight.at(kr.weathertalk.weather.WeatherStubs.kst(localTime))).isEqualTo(expected);
	}

	@Test
	void midTermTextConversion() {
		assertThat(KmaCodes.midTermSky("구름많고 비")).isEqualTo(SkyCondition.MOSTLY_CLOUDY);
		assertThat(KmaCodes.midTermSky("흐리고 비/눈")).isEqualTo(SkyCondition.OVERCAST);
		assertThat(KmaCodes.midTermSky("맑음")).isEqualTo(SkyCondition.CLEAR);
		assertThat(KmaCodes.midTermPrecipitationType("흐리고 비/눈")).isEqualTo(PrecipitationType.RAIN_SNOW);
		assertThat(KmaCodes.midTermPrecipitationType("구름많고 소나기")).isEqualTo(PrecipitationType.SHOWER);
		assertThat(KmaCodes.midTermPrecipitationType("흐리고 눈")).isEqualTo(PrecipitationType.SNOW);
		assertThat(KmaCodes.midTermPrecipitationType("구름많음")).isEqualTo(PrecipitationType.NONE);
	}

}
