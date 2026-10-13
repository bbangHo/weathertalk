package kr.weathertalk.feeling;

/** 민감도 보정 제안 상태 (04 §3.6). */
public enum CalibrationOfferStatus {

	/** 제안됨 — 회원당 최대 하나 */
	OFFERED,

	/** 수락됨 — member 온도 민감도 변경(출처 = 보정 제안 수락)까지 끝남 */
	ACCEPTED,

	/** 거절됨 — feeling.calibration.decline-cooldown 동안 다시 판정하지 않는다 */
	DECLINED,

	/** 만료됨 — feeling.calibration.offer-ttl 경과 */
	EXPIRED,

	/** 무효 — 회원이 다른 경로로 민감도를 바꿈 */
	INVALIDATED

}
