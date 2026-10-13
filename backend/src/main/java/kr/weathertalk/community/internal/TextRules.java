package kr.weathertalk.community.internal;

import java.util.Map;

import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;

/**
 * 본문 길이 규칙 (BR-COM-03, legacy 03-#21·07-#10). 길이는 문자(코드 포인트) 수로 센다 — 앱 입력창이 이모지를 한 글자로 세는 것과 맞춘다.
 * 최소 길이는 공백(줄바꿈 포함)을 뺀 글자 수, 최대 길이는 공백을 포함한 전체 글자 수다. 본문은 앞뒤 공백만 걷어 내고 저장한다.
 */
final class TextRules {

	private TextRules() {
	}

	/**
	 * @return 앞뒤 공백을 걷어 낸 본문
	 * @throws DomainException INVALID_REQUEST (응답 {@code reason}: BLANK / TOO_SHORT / TOO_LONG)
	 */
	static String body(String body, int minLength, int maxLength, String what) {
		if (body == null || body.isBlank()) {
			throw invalid("BLANK", what + " 본문을 입력해 주세요.");
		}
		String trimmed = body.strip();
		long visible = trimmed.codePoints().filter(c -> !Character.isWhitespace(c)).count();
		if (visible < minLength) {
			throw invalid("TOO_SHORT", what + " 본문은 공백을 빼고 " + minLength + "자 이상이어야 합니다.");
		}
		if (trimmed.codePointCount(0, trimmed.length()) > maxLength) {
			throw invalid("TOO_LONG", what + " 본문은 " + maxLength + "자 이하여야 합니다.");
		}
		return trimmed;
	}

	private static DomainException invalid(String reason, String message) {
		return new DomainException(ErrorCode.INVALID_REQUEST, message, Map.of("reason", reason));
	}

}
