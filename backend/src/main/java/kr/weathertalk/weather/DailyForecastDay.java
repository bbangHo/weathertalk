package kr.weathertalk.weather;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 일별 예보 하루 (03 §3.5). 어느 원천도 덮지 않는 날은 날짜만 있고 나머지는 null(다른 날 값으로 채우지 않음).
 * 최고·최저는 서버가 정한 하나의 정의만 쓴다(legacy 04-#27).
 */
public record DailyForecastDay(LocalDate date, Double minTemperature, Double maxTemperature, SkyCondition amSky,
		SkyCondition pmSky, Integer amPrecipitationProbability, Integer pmPrecipitationProbability,
		PrecipitationType amPrecipitationType, PrecipitationType pmPrecipitationType, DailyForecastSource source,
		Instant issuedAt) {

	public static DailyForecastDay empty(LocalDate date) {
		return new DailyForecastDay(date, null, null, null, null, null, null, null, null, null, null);
	}

	public boolean isEmpty() {
		return source == null;
	}

}
