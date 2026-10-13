package kr.weathertalk.community.internal.image;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * 사진 객체 저장소 포트. 운영 구현은 S3 표준 API({@link S3ImageStorage}, SeaweedFS)다.
 * 설정이 비어 있으면 {@link #configured()}가 false이고, 나머지 메서드는 {@link StorageUnavailableException}을 던진다.
 */
public interface ImageStorage {

	boolean configured();

	/**
	 * 앱이 직접 올릴 사전 서명 PUT 주소. 형식·크기를 서명에 넣어 다른 형식·크기로는 올릴 수 없게 한다.
	 *
	 * @return 주소, 앱이 그대로 붙여야 하는 헤더, 만료 시각
	 */
	PresignedUpload presignUpload(String key, String contentType, long contentLength, Duration ttl);

	/** 표시용 사전 서명 GET 주소. 서명만 하고 저장소를 호출하지 않는다. */
	String presignView(String key, Duration ttl);

	/** 객체 크기. 없으면 빈 값. */
	Optional<Long> size(String key);

	/** 객체 전체를 읽는다. 크기 확인 뒤에만 부른다. 없으면 빈 값. */
	Optional<byte[]> read(String key);

	void write(String key, byte[] content, String contentType);

	/** 없는 키를 지워도 성공이다(정리 재시도 멱등). */
	void delete(String key);

	record PresignedUpload(String url, Map<String, String> headers, Instant expiresAt) {
	}

	/** 저장소 미설정·연결 실패. 앱에는 '잠시 후 다시 시도'로 응답한다(FR-COM-01 저장소 오류). */
	class StorageUnavailableException extends RuntimeException {

		public StorageUnavailableException(String message, Throwable cause) {
			super(message, cause);
		}

	}

}
