package kr.weathertalk.outfit;

import java.time.Instant;

/**
 * 외출 시각 옷 추천 결과 (FR-OUT-02).
 *
 * @param recommendation 가장 추운 시각의 온도 태그 기준 옷차림(어느 시각이든 비·눈이면 강수 보정 포함)
 * @param layeringAdvised 시각 간 온도 태그 단계 차가 outfit.layering-gap 이상
 * @param layeringGuide 겹쳐 입기 안내 문구. 안내하지 않으면 null
 * @param basisDepartureAt 기준으로 쓴 외출 시각(가장 추운 시각, 같으면 이른 시각)
 */
public record DepartureOutfitRecommendation(OutfitRecommendation recommendation, boolean layeringAdvised,
		String layeringGuide, Instant basisDepartureAt, int basisTemperatureLevel) {
}
