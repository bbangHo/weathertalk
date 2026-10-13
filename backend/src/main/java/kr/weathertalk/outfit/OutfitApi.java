package kr.weathertalk.outfit;

import java.util.List;

/**
 * outfit 모듈의 공개 인터페이스 (contracts.md §2.5). 온도 태그·강수 여부·외출 시각은 입력으로만 받는다(BR-OUT-02) —
 * 호출자가 feeling의 민감도별 체감 온도 태그 단계(1~7)와 weather의 강수 여부를 넘긴다.
 */
public interface OutfitApi {

	/**
	 * 옷 추천 조회 (FR-OUT-01).
	 *
	 * @param temperatureLevel 온도 체감 태그 단계(1 매우 추움 ~ 7 매우 더움)
	 * @param precipitation null이면 없음
	 * @throws kr.weathertalk.shared.error.DomainException INVALID_REQUEST — 범주에 없는 단계
	 */
	OutfitRecommendation recommend(int temperatureLevel, Precipitation precipitation);

	/**
	 * 외출 시각 옷 추천 조회 (FR-OUT-02). 가장 추운 시각 기준 + 일교차가 outfit.layering-gap 이상이면 겹쳐 입기 안내.
	 *
	 * @param conditions 외출 시각별 조건. '산출 불가' 시각은 호출자가 빼고 넘긴다
	 * @throws kr.weathertalk.shared.error.DomainException INVALID_REQUEST — 빈 목록, 범주에 없는 단계
	 */
	DepartureOutfitRecommendation recommendForDepartures(List<DepartureCondition> conditions);

}
