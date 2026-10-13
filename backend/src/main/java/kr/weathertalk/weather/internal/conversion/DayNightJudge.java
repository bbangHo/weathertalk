package kr.weathertalk.weather.internal.conversion;

import java.time.Instant;
import java.time.LocalTime;

import kr.weathertalk.shared.time.TimeConfig;
import kr.weathertalk.weather.DayNight;
import kr.weathertalk.weather.internal.config.WeatherProperties;

/** 낮·밤 (BR-WEA-23): day-start 이상 night-start 미만이면 낮(Asia/Seoul). */
public class DayNightJudge {

	private final WeatherProperties.DayNight rule;

	public DayNightJudge(WeatherProperties.DayNight rule) {
		this.rule = rule;
	}

	public DayNight at(Instant instant) {
		LocalTime time = instant.atZone(TimeConfig.SEOUL).toLocalTime();
		return !time.isBefore(rule.dayStart()) && time.isBefore(rule.nightStart()) ? DayNight.DAY : DayNight.NIGHT;
	}

}
