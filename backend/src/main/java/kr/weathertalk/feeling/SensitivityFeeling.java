package kr.weathertalk.feeling;

import java.time.Instant;

import kr.weathertalk.member.TemperatureSensitivity;
import kr.weathertalk.shared.Ids.NeighborhoodId;

/**
 * 민감도별 체감 (FR-FEL-04, BR-FEL-13). 체감온도 수치와는 별개다.
 *
 * @param basisTime 기준 시각(정시)
 * @param humidityTag 산출 단계가 제공할 때만(동네 체감·체감 매핑)
 * @param windTag 산출 단계가 제공할 때만
 * @param sampleCount 근거 표본 수(동네 체감·체감 매핑일 때)
 * @param mappingVersion 체감 매핑일 때만
 * @param weatherBasedTemperatureTag 고정 기준표만으로 계산한 기상값 기준 온도 태그. 기온을 얻지 못하면 null
 * @param derivedAt 산출 시각. 체감 피드백 때 앱이 그대로 돌려보낸다(feeling.feedback.max-shown-age)
 */
public record SensitivityFeeling(NeighborhoodId neighborhoodId, TemperatureSensitivity temperatureSensitivity,
		Instant basisTime, TimeKind timeKind, TemperatureTag temperatureTag, HumidityTag humidityTag, WindTag windTag,
		DerivationSource source, Integer sampleCount, Integer mappingVersion, TemperatureTag weatherBasedTemperatureTag,
		Instant derivedAt) {

	/** 기준 시각이 현재 정시와 같으면 현재, 이후면 미래, 이전이면 과거 (FR-FEL-04 규칙 1). */
	public enum TimeKind {

		CURRENT, FUTURE, PAST

	}

}
