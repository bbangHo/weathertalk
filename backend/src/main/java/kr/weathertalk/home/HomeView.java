package kr.weathertalk.home;

import kr.weathertalk.weather.CurrentObservation;
import kr.weathertalk.weather.DailyForecast;
import kr.weathertalk.weather.HourlyForecast;

/**
 * 홈 조합 결과 (07 §1.2). 섹션마다 독립 상태를 가진다. 이후 마일스톤이 섹션을 필드로 추가한다
 * (M3 민감도별 체감·옷 추천·어제 비교, M4 인기글, M5 현장 제보·차이 배지).
 *
 * @param currentWeather weather 현재 관측 조회 그대로(낮·밤·신선도 포함, home은 판정하지 않음)
 */
public record HomeView(ViewingNeighborhood neighborhood, HomeSection<CurrentObservation> currentWeather,
		HomeSection<HourlyForecast> hourlyForecast, HomeSection<DailyForecast> dailyForecast) {
}
