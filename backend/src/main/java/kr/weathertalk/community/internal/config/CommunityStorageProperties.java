package kr.weathertalk.community.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 사진 객체 저장소(SeaweedFS, S3 API) 연결 설정. 운영 파라미터(§7)가 아니라 연동 설정이라 {@link CommunityProperties}와 나눴다.
 * 주소·키가 비어 있으면 기동은 되고 사진 기능만 '일시적으로 사용할 수 없음'으로 응답한다.
 *
 * @param endpoint 백엔드가 접근하는 내부 주소(path-style)
 * @param publicEndpoint 사전 서명 주소의 기준 주소(앱이 직접 접근). 비면 endpoint
 */
@Validated
@ConfigurationProperties("community.storage")
public record CommunityStorageProperties(String endpoint, String publicEndpoint, String region, String bucket,
		String accessKey, String secretKey) {

	public boolean configured() {
		return notBlank(endpoint) && notBlank(region) && notBlank(bucket) && notBlank(accessKey) && notBlank(secretKey);
	}

	public String effectivePublicEndpoint() {
		return notBlank(publicEndpoint) ? publicEndpoint : endpoint;
	}

	private static boolean notBlank(String value) {
		return value != null && !value.isBlank();
	}

	@Override
	public String toString() {
		return "CommunityStorageProperties[endpoint=" + endpoint + ", publicEndpoint=" + publicEndpoint + ", bucket="
				+ bucket + ", keys=" + (configured() ? "set" : "unset") + "]";
	}

}
