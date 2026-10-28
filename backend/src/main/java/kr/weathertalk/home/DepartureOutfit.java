package kr.weathertalk.home;

import java.util.List;

import kr.weathertalk.feeling.SensitivityFeeling;
import kr.weathertalk.outfit.DepartureOutfitRecommendation;

/**
 * 외출 시각 기준 섹션 (07 §1.2, SCR-01 8번): 외출 시각별 기준 그룹 체감과 outfit의 외출 시각 옷 추천.
 *
 * @param departures '산출 불가'인 시각은 빠진다
 * @param recommendation 산출된 시각이 하나도 없으면 null(섹션 데이터 없음)
 */
public record DepartureOutfit(List<Departure> departures, DepartureOutfitRecommendation recommendation) {

	public record Departure(java.time.Instant departureAt, SensitivityFeeling feeling) {
	}

}
