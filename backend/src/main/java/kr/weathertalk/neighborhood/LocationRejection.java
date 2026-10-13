package kr.weathertalk.neighborhood;

/**
 * 현재 동네 확정 거부 사유 (FR-NBR-03 결과). 호출 모듈은 어떤 사유든 CURRENT_NEIGHBORHOOD_UNVERIFIED로 응답한다.
 */
public enum LocationRejection {

	/** 좌표 또는 정확도가 없다. */
	LOCATION_MISSING,

	/** 위경도 범위를 벗어났거나 정확도가 음수 등 형식이 틀렸다. */
	LOCATION_INVALID,

	/** 정확도가 neighborhood.max-accuracy-for-write보다 나쁘다. */
	ACCURACY_TOO_LOW,

	/** 어느 유효 동네에도 속하지 않는다(바다·국외). */
	OUTSIDE_SERVICE_AREA

}
