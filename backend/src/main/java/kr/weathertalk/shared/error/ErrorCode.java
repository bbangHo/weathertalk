package kr.weathertalk.shared.error;

import org.springframework.http.HttpStatus;

/**
 * 앱이 분기 처리해야 하는 공통 오류 (contracts.md §4). 모듈 고유 오류는 각 모듈이 이 형식을 따라 추가한다.
 */
public enum ErrorCode {

	/** 비회원이 회원 전용 기능을 요청했다. 앱은 가입 유도 흐름으로 보낸다. */
	SIGNUP_REQUIRED(HttpStatus.UNAUTHORIZED),

	/** 토큰이 없거나 잘못되었거나 폐기되었다. 비회원으로 낮추지 않는다. */
	AUTHENTICATION_FAILED(HttpStatus.UNAUTHORIZED),

	/** 쓰기 직전 좌표로 현재 동네를 확정하지 못했다. */
	CURRENT_NEIGHBORHOOD_UNVERIFIED(HttpStatus.UNPROCESSABLE_CONTENT),

	/** 필수 약관 재동의 전에는 쓰기 기능을 쓸 수 없다. */
	TERMS_REAGREEMENT_REQUIRED(HttpStatus.FORBIDDEN),

	INVALID_REQUEST(HttpStatus.BAD_REQUEST),

	/** 같은 요청 식별자의 첫 요청이 아직 처리 중이다. 앱은 잠시 후 같은 식별자로 다시 보낸다. */
	REQUEST_IN_PROGRESS(HttpStatus.CONFLICT),

	NOT_FOUND(HttpStatus.NOT_FOUND);

	private final HttpStatus status;

	ErrorCode(HttpStatus status) {
		this.status = status;
	}

	public HttpStatus status() {
		return status;
	}

}
