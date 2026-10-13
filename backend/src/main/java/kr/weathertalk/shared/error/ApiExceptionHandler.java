package kr.weathertalk.shared.error;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 오류 응답은 RFC 9457 Problem Detail 형식으로 내보내고, 앱이 분기할 수 있도록 {@code code} 속성을 붙인다.
 */
@RestControllerAdvice
class ApiExceptionHandler {

	@ExceptionHandler(DomainException.class)
	ResponseEntity<ProblemDetail> handle(DomainException e) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(e.code().status(), e.getMessage());
		problem.setProperty("code", e.code().name());
		e.details().forEach(problem::setProperty);
		return ResponseEntity.status(e.code().status()).body(problem);
	}

	/**
	 * 요청 본문·파라미터 형식 오류도 같은 형식(INVALID_REQUEST)으로 낸다.
	 * 원래 예외 메시지는 입력값(좌표 등)을 되풀이할 수 있어 응답·로그에 싣지 않는다(ADR 0009).
	 */
	@ExceptionHandler({ HttpMessageNotReadableException.class, MethodArgumentNotValidException.class,
			MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class })
	ResponseEntity<ProblemDetail> handleMalformed(Exception e) {
		return handle(new DomainException(ErrorCode.INVALID_REQUEST, "요청 형식이 올바르지 않습니다."));
	}

}
