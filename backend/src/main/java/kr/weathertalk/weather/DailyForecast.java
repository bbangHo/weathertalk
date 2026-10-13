package kr.weathertalk.weather;

import java.util.List;

import kr.weathertalk.shared.Ids.NeighborhoodId;

/**
 * 중기 예보 조회(일별 예보) 결과 (FR-WEA-03). 오늘부터 weather.mid-term-days일.
 *
 * @param freshness 값을 낸 원천들 중 나쁜 쪽. 낸 날이 없으면 NONE
 */
public record DailyForecast(NeighborhoodId neighborhoodId, Freshness freshness, List<DailyForecastDay> days) {

	public boolean isEmpty() {
		return days.stream().allMatch(DailyForecastDay::isEmpty);
	}

}
