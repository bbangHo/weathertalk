package kr.weathertalk.shared.error;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 오류 응답은 RFC 9457 Problem Detail 형식으로 내보내고, 앱이 분기할 수 있도록 {@code code} 속성을 붙인다.
 */
@RestControllerAdvice
class ApiExceptionHandler {

	@ExceptionHandler(DomainException.class)
	ResponseEntity<ProblemDetail> handle(DomainException e) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(e.code().status(), e.getMessage());
		problem.setProperty("code", e.code().name());
		return ResponseEntity.status(e.code().status()).body(problem);
	}

}
