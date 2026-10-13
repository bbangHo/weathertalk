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

	NOT_FOUND(HttpStatus.NOT_FOUND),

	/** 일시적인 서버 측 실패(재시도 한도 초과 등). 앱은 잠시 후 다시 시도하도록 안내한다. */
	TEMPORARILY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),

	// ── member ──

	/** 가입 티켓이 없거나 만료·사용됨. 앱은 로그인부터 다시 한다(FR-MEM-03). */
	SIGNUP_TICKET_INVALID(HttpStatus.UNAUTHORIZED),

	/** 현재 시행 중인 필수 약관 중 동의하지 않았거나 구버전에 동의한 것이 있다(BR-MEM-06). */
	REQUIRED_TERMS_NOT_AGREED(HttpStatus.UNPROCESSABLE_CONTENT),

	/** 다른 활동 회원이 쓰는 닉네임이다(BR-MEM-15). */
	NICKNAME_TAKEN(HttpStatus.CONFLICT),

	/** 닉네임 변경 주기가 지나지 않았다. 응답의 {@code nextChangeAvailableAt}에 다음 변경 가능 시각을 싣는다. */
	NICKNAME_CHANGE_TOO_SOON(HttpStatus.UNPROCESSABLE_CONTENT),

	// ── neighborhood ──

	/** 좌표가 어느 유효 동네에도 속하지 않는다(바다·국외 등). 조회용 판정(FR-NBR-01·02) 결과. */
	NEIGHBORHOOD_NOT_RESOLVABLE(HttpStatus.UNPROCESSABLE_CONTENT),

	/** 관심 동네 최대 개수를 넘는다(BR-NBR-08). */
	INTEREST_NEIGHBORHOOD_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_CONTENT);

	private final HttpStatus status;

	ErrorCode(HttpStatus status) {
		this.status = status;
	}

	public HttpStatus status() {
		return status;
	}

}
