package kr.weathertalk.home;

import kr.weathertalk.feeling.SensitivityFeelings;
import kr.weathertalk.feeling.YesterdayComparison;
import kr.weathertalk.outfit.OutfitRecommendation;
import kr.weathertalk.weather.CurrentObservation;
import kr.weathertalk.weather.DailyForecast;
import kr.weathertalk.weather.HourlyForecast;

/**
 * 홈 조합 결과 (07 §1.2). 섹션마다 독립 상태를 가진다. 이후 마일스톤이 섹션을 필드로 추가한다
 * (M4 인기글, M5 현장 제보·차이 배지, M6 민감도 보정 제안).
 *
 * @param currentWeather weather 현재 관측 조회 그대로(낮·밤·신선도 포함, home은 판정하지 않음)
 * @param personal 내 그룹(강조·옷 추천·어제 비교 기준)
 * @param sensitivityFeelings feeling 민감도별 체감 3그룹 그대로
 * @param feedbackStatus 체감 피드백 버튼 상태. 비회원은 조회하지 않아 null(07 §1.3)
 * @param outfit 기준 그룹 온도 태그 + 현재 강수 여부로 옷 추천. 민감도별 체감이 실패하면 PREREQUISITE_FAILED
 * @param yesterdayComparison 기준 그룹의 어제와 비교. 민감도별 체감이 실패하면 PREREQUISITE_FAILED
 * @param departureOutfit 외출 시각 기준 체감·옷차림. 외출 시각을 보내지 않으면 null(섹션 생략)
 */
public record HomeView(ViewingNeighborhood neighborhood, HomeSection<CurrentObservation> currentWeather,
		HomeSection<HourlyForecast> hourlyForecast, HomeSection<DailyForecast> dailyForecast, PersonalBasis personal,
		HomeSection<SensitivityFeelings> sensitivityFeelings, HomeSection<FeedbackStatus> feedbackStatus,
		HomeSection<OutfitRecommendation> outfit, HomeSection<YesterdayComparison> yesterdayComparison,
		HomeSection<DepartureOutfit> departureOutfit) {
}
