package kr.weathertalk.feeling;

/** 산출 출처: 민감도별 체감이 어느 단계에서 나왔는지 (04 §2, BR-FEL-13). */
public enum DerivationSource {

	/** 이 동네 주민 기록 */
	NEIGHBORHOOD_FEELING,

	/** 비슷한 날씨의 기록 (M6에서 켬) */
	FEELING_MAPPING,

	/** 기본 기준(고정 기준표) */
	REFERENCE_TABLE

}
