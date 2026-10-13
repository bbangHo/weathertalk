package kr.weathertalk.member.internal.config;

import java.time.Duration;
import java.util.List;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 소셜 로그인 제공자 연결 설정(운영 파라미터 아님, BR-MEM-03·FR-MEM-09). 키·ID는 환경 변수(루트 .env)로 받는다.
 * 값이 비어 있어도 기동은 되고, 그 제공자의 로그인은 '일시적으로 사용할 수 없음', 연결 해제는 재시도 대기가 된다.
 *
 * @param timeout 제공자 호출 제한시간
 * @param jwksCacheTtl 공개키 목록(JWKS) 캐시 수명. 모르는 kid가 오면 수명 전이라도 다시 받는다(키 회전 대응)
 * @param jwksRefreshMinInterval 모르는 kid로 인한 재조회의 최소 간격(위조 토큰 폭주로 제공자를 두드리지 않게)
 * @param credentialKey 제공자 연결 해제용 자격 암호화 키(MEMBER_PROVIDER_TOKEN_KEY). 비면 자격을 보관하지 않는다
 */
@Validated
@ConfigurationProperties("member.providers")
public record MemberProviderProperties(
		@NotNull @DurationMin(millis = 100) Duration timeout,
		@NotNull @DurationMin(seconds = 1) Duration jwksCacheTtl,
		@NotNull @DurationMin(seconds = 0) Duration jwksRefreshMinInterval,
		String credentialKey,
		@NotNull @Valid Kakao kakao,
		@NotNull @Valid Apple apple,
		@NotNull @Valid Google google) {

	/**
	 * @param appId 우리 앱 ID(숫자). 토큰 정보의 app_id와 같아야 한다
	 * @param adminKey 어드민 키 — 탈퇴 시 연결 끊기(unlink)
	 */
	public record Kakao(@NotBlank String apiBaseUrl, String appId, String adminKey) {

		public boolean loginConfigured() {
			return appId != null && !appId.isBlank();
		}

		@Override
		public String toString() {
			return "Kakao[apiBaseUrl=" + apiBaseUrl + ", appId=" + appId + ", adminKey=***]";
		}

	}

	/**
	 * @param baseUrl https://appleid.apple.com (공개키 /auth/keys, 토큰 /auth/token, 철회 /auth/revoke)
	 * @param issuer identity token iss이자 클라이언트 시크릿 aud
	 * @param clientIds identity token aud로 허용할 번들 ID·Services ID
	 * @param privateKeyPath Sign in with Apple 키(.p8) 파일 경로 — 클라이언트 시크릿(ES256) 서명
	 */
	public record Apple(@NotBlank String baseUrl, @NotBlank String issuer, List<String> clientIds, String teamId,
			String keyId, String privateKeyPath, @NotNull @DurationMin(minutes = 1) Duration clientSecretTtl) {

		public boolean loginConfigured() {
			return clientIds != null && clientIds.stream().anyMatch(id -> id != null && !id.isBlank());
		}

		public boolean clientSecretConfigured() {
			return teamId != null && !teamId.isBlank() && keyId != null && !keyId.isBlank() && privateKeyPath != null
					&& !privateKeyPath.isBlank();
		}

	}

	/**
	 * @param clientIds ID 토큰 aud로 허용할 OAuth 클라이언트 ID(Android·iOS·웹)
	 * @param serverClientId 서버 인가 코드를 교환할 웹 클라이언트 ID(연결 해제용 토큰 획득)
	 * @param serverClientSecret 그 클라이언트 시크릿
	 */
	public record Google(@NotBlank String jwksUrl, @NotNull List<String> issuers, List<String> clientIds,
			@NotBlank String tokenUrl, @NotBlank String revokeUrl, String serverClientId, String serverClientSecret) {

		public boolean loginConfigured() {
			return clientIds != null && clientIds.stream().anyMatch(id -> id != null && !id.isBlank());
		}

		public boolean codeExchangeConfigured() {
			return serverClientId != null && !serverClientId.isBlank() && serverClientSecret != null
					&& !serverClientSecret.isBlank();
		}

		@Override
		public String toString() {
			return "Google[clientIds=" + clientIds + ", serverClientId=" + serverClientId + ", serverClientSecret=***]";
		}

	}

	@Override
	public String toString() {
		return "MemberProviderProperties[timeout=" + timeout + ", credentialKey=***, kakao=" + kakao + ", apple=" + apple
				+ ", google=" + google + "]";
	}

}
