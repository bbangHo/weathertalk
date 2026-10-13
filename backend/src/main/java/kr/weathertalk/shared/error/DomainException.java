package kr.weathertalk.shared.error;

import java.util.Map;

public class DomainException extends RuntimeException {

	private final ErrorCode code;

	private final Map<String, Object> details;

	public DomainException(ErrorCode code, String message) {
		this(code, message, Map.of());
	}

	/**
	 * @param details 앱이 분기·안내에 쓰는 추가 속성(예: 다음 변경 가능 시각, 거부 사유). 응답 Problem Detail 속성으로 나간다.
	 * 좌표 같은 위치 정보는 넣지 않는다(ADR 0009).
	 */
	public DomainException(ErrorCode code, String message, Map<String, Object> details) {
		super(message);
		this.code = code;
		this.details = Map.copyOf(details);
	}

	public ErrorCode code() {
		return code;
	}

	public Map<String, Object> details() {
		return details;
	}

}
