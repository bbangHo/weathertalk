package kr.weathertalk.community.internal;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;

/**
 * 목록 커서 = 직전 페이지 마지막 항목의 (작성 시각, ID) (BR-COM-08, FR-COM-10). 앱에는 불투명한 문자열로 준다.
 * 같은 시각의 항목은 ID로 순서를 정하고, 다음 페이지는 커서보다 엄격히 뒤인 항목만 가져오므로 중복·누락이 없다(legacy 03-#1).
 */
record PageCursor(Instant createdAt, UUID id) {

	String encode() {
		return Base64.getUrlEncoder()
			.withoutPadding()
			.encodeToString((createdAt + "|" + id).getBytes(StandardCharsets.UTF_8));
	}

	/** @return 커서가 없으면(null·빈 값) null — 첫 페이지 */
	static PageCursor decode(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
			int bar = decoded.indexOf('|');
			return new PageCursor(Instant.parse(decoded.substring(0, bar)), UUID.fromString(decoded.substring(bar + 1)));
		}
		catch (RuntimeException e) {
			throw new DomainException(ErrorCode.INVALID_REQUEST, "목록 커서 형식이 올바르지 않습니다.");
		}
	}

}
