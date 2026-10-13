package kr.weathertalk.weather;

import java.time.Instant;

/**
 * 대기질 측정 (03 §3.6). 측정 시각은 기상 관측 시각과 따로 간다. 결측 항목은 null(0으로 채우지 않음).
 *
 * @param combinedGrade 통합 미세먼지 등급 = PM10·PM2.5 등급 중 나쁜 쪽
 */
public record AirQuality(String stationName, Instant measuredAt, Double pm10, Double pm25, AirGrade pm10Grade,
		AirGrade pm25Grade, AirGrade combinedGrade, AirGrade ozoneGrade, Freshness freshness) {

	public static AirQuality none() {
		return new AirQuality(null, null, null, null, null, null, null, null, Freshness.NONE);
	}

	public boolean hasValue() {
		return pm10 != null || pm25 != null || combinedGrade != null || ozoneGrade != null;
	}

}
