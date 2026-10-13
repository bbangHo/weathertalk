package kr.weathertalk.feeling;

/** 기록 출처 (04 §2·§3.2). */
public enum FeelingRecordSource {

	/** 회원이 체감 태그를 골라 남김 */
	DIRECT,

	/** 체감 피드백에서 변환 */
	FEEDBACK,

	/** 직접 기록의 온도만 피드백으로 바뀐 것 (BR-FEL-04 예외) */
	DIRECT_WITH_FEEDBACK

}
