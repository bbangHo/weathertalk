package kr.weathertalk.neighborhood;

/**
 * 현재 동네 확정 거부 사유 (FR-NBR-03 결과). 호출 모듈은 어떤 사유든 CURRENT_NEIGHBORHOOD_UNVERIFIED로 응답한다.
 */
public enum LocationRejection {

	/** 동네 ID가 없다. */
	NEIGHBORHOOD_MISSING,

	/** 동네 기준 데이터에 없는 동네 ID다. */
	NEIGHBORHOOD_UNKNOWN,

	/** 폐지된(유효하지 않은) 동네다. */
	NEIGHBORHOOD_RETIRED

}
