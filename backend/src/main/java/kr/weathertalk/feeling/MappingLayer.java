package kr.weathertalk.feeling;

/** 체감 매핑에서 값을 얻은 셀 층 (04 §3.5, BR-FEL-13 2단계). 위에서 아래로 대체한다. */
public enum MappingLayer {

	/** 세부 셀: 온도 민감도 × 계절 × 기온·습도·풍속 구간 */
	DETAIL,

	/** 계절 없는 셀: 온도 민감도 × 기온·습도·풍속 구간 */
	NO_SEASON,

	/** 축약 셀: 온도 민감도 × 기온 구간 */
	COARSE

}
