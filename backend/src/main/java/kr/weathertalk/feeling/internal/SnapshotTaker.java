package kr.weathertalk.feeling.internal;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import kr.weathertalk.feeling.internal.config.FeelingProperties;
import kr.weathertalk.shared.Ids.NeighborhoodId;
import kr.weathertalk.weather.CurrentObservation;
import kr.weathertalk.weather.ObservationItem;
import kr.weathertalk.weather.WeatherApi;

/**
 * 기록 시점의 관측 스냅샷을 만든다 (04 §3.3, BR-FEL-05). 확보에 실패해도 기록은 저장한다(데이터량 우선):
 * 조회 실패, 관측 시각 없음·feeling.snapshot.max-age 초과, 기온·습도·풍속 중 하나라도 없음 → '미확보'.
 * 미세먼지 같은 일부 값만 없으면 그 값만 비우고 '확보'다.
 */
@Component
class SnapshotTaker {

	private static final Logger log = LoggerFactory.getLogger(SnapshotTaker.class);

	private final WeatherApi weatherApi;
	private final FeelingProperties properties;

	SnapshotTaker(WeatherApi weatherApi, FeelingProperties properties) {
		this.weatherApi = weatherApi;
		this.properties = properties;
	}

	ObservationSnapshot take(NeighborhoodId neighborhoodId, Instant recordedAt) {
		CurrentObservation o;
		try {
			o = weatherApi.findCurrentObservation(neighborhoodId);
		}
		catch (RuntimeException e) {
			log.warn("관측 스냅샷 확보 실패, 미확보로 저장: {} ({})", neighborhoodId.value(), e.getClass().getSimpleName());
			return ObservationSnapshot.missing();
		}
		boolean complete = o.observedAt() != null && o.temperature() != null && o.humidity() != null
				&& o.windSpeed() != null;
		boolean fresh = o.observedAt() != null
				&& Duration.between(o.observedAt(), recordedAt).compareTo(properties.snapshot().maxAge()) <= 0;
		var air = o.airQuality();
		Double pm10 = air == null ? null : air.pm10();
		Double pm25 = air == null ? null : air.pm25();
		return new ObservationSnapshot(complete && fresh, o.observedAt(), o.temperature(), o.humidity(),
				o.windSpeed(), pm10, pm25, o.precipitationType() == null ? null : o.precipitationType().name(),
				o.sources().get(ObservationItem.TEMPERATURE), o.sources().get(ObservationItem.HUMIDITY),
				o.sources().get(ObservationItem.WIND_SPEED),
				pm10 != null || pm25 != null ? o.sources().get(ObservationItem.AIR_QUALITY) : null);
	}

}
