package kr.weathertalk.outfit;

/**
 * 옷 추천 결과 (FR-OUT-01).
 *
 * @param tableVersion 사용한 옷차림 매핑표 버전(BR-OUT-04)
 */
public record OutfitRecommendation(int temperatureLevel, Precipitation precipitation, Outfit outfit,
		String tableVersion) {
}
