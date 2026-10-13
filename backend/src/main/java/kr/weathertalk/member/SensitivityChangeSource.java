package kr.weathertalk.member;

/**
 * 온도 민감도 변경 출처 (FR-MEM-08). 시스템이 스스로 바꾸는 출처는 없다(BR-MEM-12).
 */
public enum SensitivityChangeSource {

	/** 회원이 마이페이지에서 직접 변경 */
	DIRECT,

	/** 회원이 민감도 보정 제안을 수락 */
	CALIBRATION_ACCEPTED

}
