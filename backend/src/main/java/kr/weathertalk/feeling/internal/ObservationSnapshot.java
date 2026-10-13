package kr.weathertalk.feeling.internal;

import java.time.Instant;

import kr.weathertalk.weather.ValueSource;

/**
 * 관측 스냅샷 (04 §3.3). 기록과 함께 저장되고 이후 바뀌지 않는다(덮어쓰기 때는 통째로 교체). 체감온도 수치는 저장하지 않는다.
 *
 * @param secured 확보 여부. 미확보 기록도 저장한다(BR-FEL-05)
 * @param precipitationType weather 강수 형태 이름(NONE/RAIN/…)
 */
record ObservationSnapshot(boolean secured, Instant observedAt, Double temperature, Double humidity, Double windSpeed,
		Double pm10, Double pm25, String precipitationType, ValueSource temperatureSource, ValueSource humiditySource,
		ValueSource windSpeedSource, ValueSource airSource) {

	static final String SECURED = "SECURED";

	static final String MISSING = "MISSING";

	static ObservationSnapshot missing() {
		return new ObservationSnapshot(false, null, null, null, null, null, null, null, null, null, null, null);
	}

	String status() {
		return secured ? SECURED : MISSING;
	}

}
