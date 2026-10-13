package kr.weathertalk.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;

import org.junit.jupiter.api.Test;

import kr.weathertalk.feeling.TemperatureTag;
import kr.weathertalk.member.TemperatureSensitivity;
import kr.weathertalk.weather.PrecipitationType;
import kr.weathertalk.weather.SkyCondition;

/** 문구 규칙 단위 테스트 (06 §4.1, BR-NOTI-01·08). */
class MessageRulesTests {

	/** legacy 05-#8: 문구에 쓰는 모든 열거값에 표시명이 있다(새 값이 생기면 이 테스트가 깨져 표시명 추가를 강제한다). */
	@Test
	void everyEnumValueHasDisplayName_05_8() {
		for (SkyCondition sky : SkyCondition.values()) {
			assertThat(MessageTemplates.skyName(sky)).isNotBlank();
		}
		for (TemperatureTag tag : TemperatureTag.values()) {
			assertThat(MessageTemplates.temperatureTagName(tag)).isNotBlank().doesNotContain("_");
		}
		for (TemperatureSensitivity sensitivity : TemperatureSensitivity.values()) {
			assertThat(MessageTemplates.sensitivityName(sensitivity)).endsWith("분");
		}
		for (PrecipitationType type : PrecipitationType.values()) {
			if (type.isPrecipitating()) {
				assertThat(MessageTemplates.forecastPrecipitationName(type)).isNotBlank();
			}
		}
		assertThat(MessageTemplates.forecastPrecipitationName(PrecipitationType.RAIN_SNOW)).isEqualTo("비나 눈");
		assertThat(MessageTemplates.forecastPrecipitationName(PrecipitationType.NONE)).isNull();
	}

	/** 시각 표기 "오후 3시", "오후 3시 30분" (Asia/Seoul, legacy 06-#12). */
	@Test
	void timeNameUsesSeoulAndKoreanStyle() {
		assertThat(MessageTemplates.timeName(java.time.Instant.parse("2026-10-13T06:00:00Z"))).isEqualTo("오후 3시");
		assertThat(MessageTemplates.timeName(java.time.Instant.parse("2026-10-13T06:30:00Z"))).isEqualTo("오후 3시 30분");
		assertThat(MessageTemplates.timeName(java.time.Instant.parse("2026-10-12T15:00:00Z"))).isEqualTo("오전 12시");
		assertThat(MessageTemplates.timeName(java.time.Instant.parse("2026-10-13T03:00:00Z"))).isEqualTo("오후 12시");
	}

	/** 심야 금지 시간은 자정을 넘긴다(22:00 이상 또는 07:00 미만). 금지 종료 시각은 그날 또는 다음 날 07:00. */
	@Test
	void quietHoursCrossMidnight() {
		QuietHours quiet = QuietHours.of(LocalTime.of(22, 0), LocalTime.of(7, 0));
		assertThat(quiet.contains(kr.weathertalk.weather.WeatherStubs.kst("2026-10-13T21:59"))).isFalse();
		assertThat(quiet.contains(kr.weathertalk.weather.WeatherStubs.kst("2026-10-13T22:00"))).isTrue();
		assertThat(quiet.contains(kr.weathertalk.weather.WeatherStubs.kst("2026-10-14T06:59"))).isTrue();
		assertThat(quiet.contains(kr.weathertalk.weather.WeatherStubs.kst("2026-10-14T07:00"))).isFalse();
		assertThat(quiet.endOf(kr.weathertalk.weather.WeatherStubs.kst("2026-10-13T23:10")))
			.isEqualTo(kr.weathertalk.weather.WeatherStubs.kst("2026-10-14T07:00"));
		assertThat(quiet.endOf(kr.weathertalk.weather.WeatherStubs.kst("2026-10-14T05:00")))
			.isEqualTo(kr.weathertalk.weather.WeatherStubs.kst("2026-10-14T07:00"));
	}

}
