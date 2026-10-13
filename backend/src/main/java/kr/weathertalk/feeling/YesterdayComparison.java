package kr.weathertalk.feeling;

import kr.weathertalk.member.TemperatureSensitivity;
import kr.weathertalk.shared.Ids.NeighborhoodId;

/**
 * 어제와 비교 (FR-FEL-05, BR-FEL-16). 오늘·어제 중 하나라도 '산출 불가'면 비교 불가(문구 없음).
 * 오늘·어제의 산출 출처가 달라도 비교하며 둘 다 담는다.
 *
 * @param levelDifference 오늘 단계 − 어제 단계
 */
public record YesterdayComparison(NeighborhoodId neighborhoodId, TemperatureSensitivity temperatureSensitivity,
		boolean comparable, Integer levelDifference, String phrase, SensitivityFeeling today,
		SensitivityFeeling yesterday) {
}
