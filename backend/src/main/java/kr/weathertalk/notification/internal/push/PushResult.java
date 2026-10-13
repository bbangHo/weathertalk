package kr.weathertalk.notification.internal.push;

/**
 * 건별 발송 결과 분류 (FR-NOTI-12).
 *
 * @param code 제공자 응답 코드(예: UNREGISTERED, UNAVAILABLE). 성공이면 null
 */
public record PushResult(Outcome outcome, String code) {

	public enum Outcome {

		SUCCESS,

		/** 무효 토큰 — 즉시 기기 발송 중지, 재시도 없음 */
		INVALID_TOKEN,

		/** 일시 오류 — 재시도 대기 */
		TRANSIENT,

		/** 메시지 오류 — 최종 실패, 운영 경보 */
		MESSAGE_ERROR

	}

	public static PushResult success() {
		return new PushResult(Outcome.SUCCESS, null);
	}

	public static PushResult invalidToken(String code) {
		return new PushResult(Outcome.INVALID_TOKEN, code);
	}

	public static PushResult transientFailure(String code) {
		return new PushResult(Outcome.TRANSIENT, code);
	}

	public static PushResult messageError(String code) {
		return new PushResult(Outcome.MESSAGE_ERROR, code);
	}

}
