package kr.weathertalk.weather;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 시간별 예보 한 칸 (03 §3.4). 칸은 날짜+시각으로 식별한다(legacy 04-#23, 07-#20).
 *
 * @param snowfall 1시간 신적설 구간(cm)
 */
public record HourlyForecastSlot(Instant forecastAt, LocalDate date, int hour, Double temperature, Double feelsLike,
		Double humidity, Double windSpeed, Integer precipitationProbability, PrecipitationType precipitationType,
		PrecipitationAmount precipitation, PrecipitationAmount snowfall, String precipitationPhrase,
		SkyCondition sky, DayNight dayNight) {
}
