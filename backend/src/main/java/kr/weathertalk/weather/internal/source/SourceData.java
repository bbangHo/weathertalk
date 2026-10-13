package kr.weathertalk.weather.internal.source;

import java.time.Instant;
import java.time.LocalDate;

import kr.weathertalk.weather.AirGrade;
import kr.weathertalk.weather.PrecipitationAmount;
import kr.weathertalk.weather.PrecipitationType;
import kr.weathertalk.weather.SkyCondition;

/**
 * 원천 응답을 도메인 값으로 바꾼 결과. 원천별 차이(코드·결측 표기·시각 형식)는 어댑터가 흡수한다(03 §3.1). 결측은 null.
 */
public final class SourceData {

	private SourceData() {
	}

	/** 초단기실황 한 시각. */
	public record Observation(Instant observedAt, Double temperature, Double humidity, Double windSpeed,
			PrecipitationAmount precipitation, PrecipitationType precipitationType) {
	}

	/** 단기예보 한 칸(날짜+시각 키). @param dailyMax TMX(그 칸에만 실림) */
	public record ShortTermSlot(Instant forecastAt, Double temperature, Double humidity, Double windSpeed,
			Integer precipitationProbability, PrecipitationType precipitationType, PrecipitationAmount precipitation,
			PrecipitationAmount snowfall, SkyCondition sky, Double dailyMax, Double dailyMin) {
	}

	/** 중기 육상예보 하루(오전·오후. 오전·오후 구분이 없는 날은 같은 값). */
	public record MidLandDay(LocalDate date, SkyCondition amSky, SkyCondition pmSky, Integer amPrecipitationProbability,
			Integer pmPrecipitationProbability, PrecipitationType amPrecipitationType,
			PrecipitationType pmPrecipitationType) {
	}

	public record MidTemperatureDay(LocalDate date, Double min, Double max) {
	}

	/** 자외선 지수 한 시각(발표 시각 + h). */
	public record UvValue(Instant forecastAt, Integer value) {
	}

	/** 대기질 측정 한 시각. */
	public record AirMeasurement(Instant measuredAt, Double pm10, Double pm25, AirGrade pm10Grade, AirGrade pm25Grade,
			AirGrade ozoneGrade) {
	}

}
