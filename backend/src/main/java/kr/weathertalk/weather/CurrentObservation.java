package kr.weathertalk.weather;

import java.time.Instant;
import java.util.Map;

import kr.weathertalk.shared.Ids.NeighborhoodId;

/**
 * 현재 관측 조회·과거 관측 조회 결과 (FR-WEA-01·13, contracts §2.3). 동네 이름은 담지 않는다(BR-WEA-21).
 * 결측 항목은 null이며 0이나 다른 항목 값으로 채우지 않는다.
 *
 * @param observedAt 기상 항목의 기준 시각: 실황 관측 시각, 예보로 대체했으면 그 예보 칸 시각. 기상 값이 하나도 없으면 null
 * @param weatherFreshness 실황 묶음의 신선도. '없음'이면 기상 항목은 예보 대체값(출처 FORECAST)이거나 없음
 * @param uv 밤이면 null(BR-WEA-23)
 * @param dayNight 조회 기준 시각의 낮·밤
 */
public record CurrentObservation(NeighborhoodId neighborhoodId, Instant observedAt, Double temperature,
		Double humidity, Double windSpeed, PrecipitationAmount precipitation, PrecipitationType precipitationType,
		String precipitationPhrase, SkyCondition sky, Double feelsLike, Map<ObservationItem, ValueSource> sources,
		Freshness weatherFreshness, AirQuality airQuality, UvIndex uv, DayNight dayNight) {

	/** 보여줄 기상·대기질·자외선 값이 하나도 없다. */
	public boolean isEmpty() {
		return sources.isEmpty();
	}

}
