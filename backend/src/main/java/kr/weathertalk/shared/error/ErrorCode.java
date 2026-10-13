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
	INTEREST_NEIGHBORHOOD_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_CONTENT),

	// ── feeling ──

	/**
	 * 체감 피드백 대상이 아니다(BR-FEL-07): 보여준 값이 현재 동네·현재 시각·회원의 현재 온도 민감도로 산출한 값이 아니거나
	 * feeling.feedback.max-shown-age보다 오래됐다. 앱은 최신 값을 다시 불러온다. 응답의 {@code reason}:
	 * NEIGHBORHOOD_MISMATCH / SENSITIVITY_MISMATCH / NOT_CURRENT_TIME / STALE.
	 */
	FEEDBACK_TARGET_INVALID(HttpStatus.UNPROCESSABLE_CONTENT),

	// ── community ──

	/** 게시글·댓글의 작성자가 아니다(수정·삭제는 작성자 본인만, FR-COM-03·04·09). */
	NOT_CONTENT_AUTHOR(HttpStatus.FORBIDDEN),

	/** 관리자 전용 명령이다. 작성자 본인이어도 쓸 수 없다(FR-COM-15, ADR 0012). */
	ADMIN_REQUIRED(HttpStatus.FORBIDDEN),

	/**
	 * 이미지를 받을 수 없다(FR-COM-01). 응답의 {@code reason}: TYPE_NOT_ALLOWED / TOO_LARGE / UNREADABLE / NOT_UPLOADED.
	 * 앱은 그 장만 다시 고르거나 올린다.
	 */
	IMAGE_REJECTED(HttpStatus.UNPROCESSABLE_CONTENT),

	/** 게시글에 붙일 수 없는 이미지다(본인이 올린 미첨부 이미지가 아님, 이미 첨부·정리됨, 장수 초과 — FR-COM-02 규칙 3). */
	POST_IMAGE_NOT_ATTACHABLE(HttpStatus.UNPROCESSABLE_CONTENT),

	/**
	 * 체감 기록을 첨부할 수 없다(BR-COM-06). 응답의 {@code reason}: NOT_OWNED / NEIGHBORHOOD_MISMATCH / TOO_OLD.
	 */
	FEELING_RECORD_NOT_ATTACHABLE(HttpStatus.UNPROCESSABLE_CONTENT),

	/** 답글을 달 수 없는 댓글이다(답글의 답글, 삭제된 댓글 — FR-COM-08). 응답의 {@code reason}: REPLY_TO_REPLY / PARENT_DELETED. */
	REPLY_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_CONTENT),

	/** 본인 게시글에는 좋아요할 수 없다(BR-COM-10). */
	SELF_LIKE_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_CONTENT),

	// ── home ──

	/**
	 * 홈에서 보는 동네를 정할 수 없다(동네 ID 없음·존재하지 않는 동네, 07 §1.4-4). 화면 전체 실패의 유일한 경우이며
	 * 앱은 동네 검색으로 보낸다. 응답의 {@code reason}: NOT_SPECIFIED / NOT_FOUND.
	 */
	VIEWING_NEIGHBORHOOD_UNRESOLVED(HttpStatus.UNPROCESSABLE_CONTENT);

	private final HttpStatus status;

	ErrorCode(HttpStatus status) {
		this.status = status;
	}

	public HttpStatus status() {
		return status;
	}

}
