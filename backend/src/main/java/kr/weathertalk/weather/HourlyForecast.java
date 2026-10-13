package kr.weathertalk.weather;

import java.time.Instant;
import java.util.List;

import kr.weathertalk.shared.Ids.NeighborhoodId;

/**
 * 시간별 예보 조회 결과 (FR-WEA-02). 칸은 현재 시 정각 이후만, 예보 시각 순.
 *
 * @param issuedAt 발표 시각. 쓸 수 있는 발표분이 없으면 null
 * @param nextPrecipitation 없으면 null
 */
public record HourlyForecast(NeighborhoodId neighborhoodId, Instant issuedAt, Freshness freshness,
		List<HourlyForecastSlot> slots, NextPrecipitation nextPrecipitation) {
}
