package kr.weathertalk.shared.idempotency;

import java.util.regex.Pattern;

import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;

/**
 * 앱이 생성성 쓰기 요청마다 붙이는 요청 식별자 (contracts.md §4 쓰기 요청 중복 방지).
 * <p>
 * 컨트롤러 메서드 파라미터로 선언하면 {@value #HEADER} 헤더에서 채워진다. 헤더가 없거나 형식이 틀리면 INVALID_REQUEST.
 */
public record IdempotencyKey(String value) {

	public static final String HEADER = "Idempotency-Key";

	// 앱은 UUID를 보내지만, 저장소 키에 그대로 들어가므로 문자 종류와 길이만 제한한다.
	private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{8,100}");

	public IdempotencyKey {
		if (value == null || !FORMAT.matcher(value).matches()) {
			throw new DomainException(ErrorCode.INVALID_REQUEST,
					HEADER + " 헤더는 8~100자의 영문·숫자·'-'·'_'여야 합니다.");
		}
	}

}
