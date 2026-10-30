package kr.weathertalk.member.provider;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import java.time.Instant;
import java.util.Map;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.nimbusds.jose.jwk.RSAKey;

/** 카카오·애플·구글·앱인토스 모의 응답(통합 테스트 공유 WireMock의 /kakao, /apple, /google, /toss). */
public class ProviderStubs {

	public static final String KAKAO_TOKEN_INFO = "/kakao/v1/user/access_token_info";
	public static final String KAKAO_USER_ME = "/kakao/v2/user/me";
	public static final String KAKAO_UNLINK = "/kakao/v1/user/unlink";
	public static final String APPLE_KEYS = "/apple/auth/keys";
	public static final String APPLE_TOKEN = "/apple/auth/token";
	public static final String APPLE_REVOKE = "/apple/auth/revoke";
	public static final String GOOGLE_CERTS = "/google/certs";
	public static final String GOOGLE_TOKEN = "/google/token";
	public static final String GOOGLE_REVOKE = "/google/revoke";
	public static final String TOSS_ANON_EXCHANGE = "/toss/api-partner/v1/apps-in-toss/users/anon-key/exchange";

	private final WireMockServer server;

	public ProviderStubs(WireMockServer server) {
		this.server = server;
	}

	/** 카카오 접근 토큰 → 회원번호(앱 ID는 우리 앱). 이메일은 있으면 사용자 정보로. */
	public void kakaoToken(String accessToken, long userId, String appId, String email) {
		server.stubFor(get(urlPathEqualTo(KAKAO_TOKEN_INFO)).withHeader("Authorization", equalTo("Bearer " + accessToken))
			.willReturn(okJson("{\"id\":" + userId + ",\"expires_in\":7199,\"app_id\":" + appId + "}")));
		String account = email == null ? "{\"has_email\":false}"
				: "{\"has_email\":true,\"is_email_valid\":true,\"is_email_verified\":true,\"email\":\"" + email + "\"}";
		server.stubFor(get(urlPathEqualTo(KAKAO_USER_ME)).withHeader("Authorization", equalTo("Bearer " + accessToken))
			.willReturn(okJson("{\"id\":" + userId + ",\"kakao_account\":" + account + "}")));
	}

	public void kakaoTokenRejected(String accessToken, int status) {
		server.stubFor(get(urlPathEqualTo(KAKAO_TOKEN_INFO)).withHeader("Authorization", equalTo("Bearer " + accessToken))
			.willReturn(aResponse().withStatus(status)
				.withHeader("Content-Type", "application/json")
				.withBody("{\"msg\":\"this access token does not exist\",\"code\":-401}")));
	}

	/** 앱인토스 식별키 교환: 인증 코드 → 식별키(anonKey). 업무 결과는 HTTP 200 + resultType. */
	public void tossAnonExchange(String code, String anonKey) {
		server.stubFor(post(urlPathEqualTo(TOSS_ANON_EXCHANGE)).withRequestBody(equalToJson("{\"code\":\"" + code + "\"}"))
			.willReturn(okJson("{\"resultType\":\"SUCCESS\",\"success\":{\"anonKey\":\"" + anonKey + "\"}}")));
	}

	/** 앱인토스 식별키 교환 업무 오류(4011 코드 무효, 4095 요청 한도 초과). HTTP는 200이다. */
	public void tossAnonExchangeFails(String code, String errorCode) {
		server.stubFor(post(urlPathEqualTo(TOSS_ANON_EXCHANGE)).withRequestBody(equalToJson("{\"code\":\"" + code + "\"}"))
			.willReturn(okJson("{\"resultType\":\"FAIL\",\"error\":{\"errorCode\":\"" + errorCode
					+ "\",\"reason\":\"test\"}}")));
	}

	public void appleKeys(RSAKey... keys) {
		server.stubFor(get(urlPathEqualTo(APPLE_KEYS)).willReturn(okJson(ProviderTestKeys.jwks(keys))));
	}

	public void googleKeys(RSAKey... keys) {
		server.stubFor(get(urlPathEqualTo(GOOGLE_CERTS)).willReturn(okJson(ProviderTestKeys.jwks(keys))));
	}

	public void appleCodeExchange(String code, String refreshToken) {
		server.stubFor(post(urlPathEqualTo(APPLE_TOKEN)).withFormParam("code", equalTo(code))
			.willReturn(okJson("{\"access_token\":\"a\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"refresh_token\":\""
					+ refreshToken + "\",\"id_token\":\"x\"}")));
	}

	public void googleCodeExchange(String code, String refreshToken) {
		server.stubFor(post(urlPathEqualTo(GOOGLE_TOKEN)).withFormParam("code", equalTo(code))
			.willReturn(okJson("{\"access_token\":\"ya29\",\"expires_in\":3599,\"refresh_token\":\"" + refreshToken
					+ "\",\"scope\":\"openid\",\"token_type\":\"Bearer\"}")));
	}

	public String appleIdToken(RSAKey key, String subject, Instant expiresAt) {
		return ProviderTestKeys.idToken(key, ProviderTestKeys.APPLE_ISSUER, ProviderTestKeys.APPLE_CLIENT_ID, subject,
				expiresAt, Map.of());
	}

	public String googleIdToken(RSAKey key, String subject, Instant expiresAt) {
		return ProviderTestKeys.idToken(key, ProviderTestKeys.GOOGLE_ISSUER, ProviderTestKeys.GOOGLE_CLIENT_IDS.get(1),
				subject, expiresAt, Map.of("email", subject + "@gmail.example", "email_verified", true));
	}

}
