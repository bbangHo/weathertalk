package kr.weathertalk.member.provider;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static kr.weathertalk.member.provider.ProviderTestKeys.APPLE_KEY;
import static kr.weathertalk.member.provider.ProviderTestKeys.APPLE_ROTATED_KEY;
import static kr.weathertalk.member.provider.ProviderTestKeys.FORGED_APPLE_KEY;
import static kr.weathertalk.member.provider.ProviderTestKeys.GOOGLE_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.SignedJWT;

import kr.weathertalk.AuthTestClient;
import kr.weathertalk.IntegrationTest;
import kr.weathertalk.MutableClock;

/**
 * 소셜 로그인 제공자 검증 (FR-MEM-02, BR-MEM-03). 실제 제공자 대신 WireMock + 테스트 RSA 키로 만든 JWKS를 쓴다.
 * 위조·만료·aud 불일치·iss 불일치·다른 앱 토큰은 모두 같은 인증 실패(401)로 끝난다(legacy 01-#12, 01-#13).
 */
@IntegrationTest
class SocialLoginTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	MutableClock clock;

	@Autowired
	WireMockServer server;

	@Autowired
	JdbcClient jdbc;

	ProviderStubs stubs;

	@BeforeEach
	void setUp() {
		clock.reset();
		server.resetAll();
		stubs = new ProviderStubs(server);
	}

	private ResultActions login(String provider, String token, String code) throws Exception {
		String body = "{\"provider\":\"" + provider + "\",\"token\":\"" + token + "\""
				+ (code == null ? "" : ",\"authorizationCode\":\"" + code + "\"") + "}";
		return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private Instant soon() {
		return clock.instant().plus(Duration.ofMinutes(10));
	}

	private static long kakaoId() {
		return Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L);
	}

	/** 카카오: 토큰 정보의 앱 ID가 우리 앱이면 회원번호로 로그인. 처음이면 가입 티켓 → 가입 완료 → 다음 로그인은 토큰. 이메일 없음도 정상. */
	@Test
	void kakaoLoginIssuesSignupTicketThenTokens() throws Exception {
		long id = kakaoId();
		stubs.kakaoToken("kakao-ok", id, ProviderTestKeys.KAKAO_APP_ID, null);

		String json = login("KAKAO", "kakao-ok", null).andExpect(status().isOk())
			.andExpect(jsonPath("$.registered").value(false))
			.andReturn()
			.getResponse()
			.getContentAsString();
		AuthTestClient auth = new AuthTestClient(mvc);
		String ticket = JsonPath.read(json, "$.signupTicket.ticket");
		String signedUp = auth.completeSignup(ticket, auth.agreeAll(), "NORMAL")
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		String memberId = JsonPath.read(signedUp, "$.member.memberId");
		assertThat(jdbc.sql("SELECT email FROM member_member WHERE id = :id")
			.param("id", UUID.fromString(memberId))
			.query(String.class)
			.optional()).isEmpty();

		login("KAKAO", "kakao-ok", null).andExpect(status().isOk())
			.andExpect(jsonPath("$.registered").value(true))
			.andExpect(jsonPath("$.member.memberId").value(memberId));
		assertThat(jdbc.sql("SELECT provider_user_id FROM member_login_account WHERE member_id = :id")
			.param("id", UUID.fromString(memberId))
			.query(String.class)
			.single()).isEqualTo(Long.toString(id));
	}

	/** legacy 01-#13: 다른 앱이 발급받은 카카오 토큰(앱 ID 불일치)은 거부. */
	@Test
	void kakaoTokenOfAnotherAppIsRejected_01_13() throws Exception {
		stubs.kakaoToken("other-app", kakaoId(), "999999", null);
		login("KAKAO", "other-app", null).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));
	}

	/** legacy 01-#12: 만료·위조 토큰(카카오가 401)은 인증 실패, 카카오 장애(5xx)는 일시 오류(재시도 안내). */
	@Test
	void kakaoRejectionIsAuthFailureAndOutageIsTemporary_01_12() throws Exception {
		stubs.kakaoTokenRejected("expired", 401);
		login("KAKAO", "expired", null).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));
		stubs.kakaoTokenRejected("outage", 503);
		login("KAKAO", "outage", null).andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("TEMPORARILY_UNAVAILABLE"));
	}

	/**
	 * 애플: identity token 서명·iss·aud·만료를 검증하고 sub로 로그인. 인가 코드는 클라이언트 시크릿(ES256, .p8 키)으로 교환해
	 * 갱신 토큰을 받아 가입한 로그인 계정에 암호화해 둔다(탈퇴 때 재로그인 없이 철회, legacy 01-#4).
	 */
	@Test
	void appleLoginVerifiesIdentityTokenAndStoresEncryptedRefreshToken() throws Exception {
		stubs.appleKeys(APPLE_KEY);
		stubs.appleCodeExchange("apple-code", "apple-refresh-123");
		String sub = "001234." + UUID.randomUUID();

		String json = login("APPLE", stubs.appleIdToken(APPLE_KEY, sub, soon()), "apple-code").andExpect(status().isOk())
			.andExpect(jsonPath("$.registered").value(false))
			.andReturn()
			.getResponse()
			.getContentAsString();
		AuthTestClient auth = new AuthTestClient(mvc);
		auth.completeSignup(JsonPath.read(json, "$.signupTicket.ticket"), auth.agreeAll(), "COLD_SENSITIVE")
			.andExpect(status().isOk());

		String stored = jdbc.sql("SELECT unlink_credential FROM member_login_account WHERE provider = 'APPLE' AND provider_user_id = :sub")
			.param("sub", sub)
			.query(String.class)
			.single();
		assertThat(stored).startsWith("v1:").doesNotContain("apple-refresh-123");
		// 가입 티켓에는 자격이 남지 않는다
		assertThat(jdbc.sql("SELECT count(*) FROM member_signup_ticket WHERE provider_user_id = :sub AND unlink_credential IS NOT NULL")
			.param("sub", sub)
			.query(Integer.class)
			.single()).isZero();

		// 교환 요청의 클라이언트 시크릿: iss=팀 ID, sub=client_id, aud=애플, kid=키 ID, .p8 키로 ES256 서명
		List<LoggedRequest> exchanges = server.findAll(postRequestedFor(urlPathEqualTo(ProviderStubs.APPLE_TOKEN)));
		assertThat(exchanges).hasSize(1);
		String secret = formParam(exchanges.get(0).getBodyAsString(), "client_secret");
		SignedJWT jwt = SignedJWT.parse(secret);
		assertThat(jwt.verify(new ECDSAVerifier(ProviderTestKeys.APPLE_CLIENT_SECRET_KEY.toECPublicKey()))).isTrue();
		assertThat(jwt.getHeader().getKeyID()).isEqualTo(ProviderTestKeys.APPLE_KEY_ID);
		assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo(ProviderTestKeys.APPLE_TEAM_ID);
		assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo(ProviderTestKeys.APPLE_CLIENT_ID);
		assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly(ProviderTestKeys.APPLE_ISSUER);
		server.verify(postRequestedFor(urlPathEqualTo(ProviderStubs.APPLE_TOKEN))
			.withRequestBody(containing("client_id=" + ProviderTestKeys.APPLE_CLIENT_ID)));
	}

	/** legacy 01-#12·#13: 위조 서명(같은 kid 다른 키)·만료·aud 불일치·iss 불일치는 모두 401. */
	@Test
	void appleForgedExpiredWrongAudienceOrIssuerAreRejected_01_12_01_13() throws Exception {
		stubs.appleKeys(APPLE_KEY);
		String sub = "001234.forged";
		login("APPLE", stubs.appleIdToken(FORGED_APPLE_KEY, sub, soon()), null).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));
		login("APPLE", stubs.appleIdToken(APPLE_KEY, sub, clock.instant().minusSeconds(1)), null)
			.andExpect(status().isUnauthorized());
		login("APPLE", ProviderTestKeys.idToken(APPLE_KEY, ProviderTestKeys.APPLE_ISSUER, "com.other.app", sub, soon(),
				Map.of()), null)
			.andExpect(status().isUnauthorized());
		login("APPLE", ProviderTestKeys.idToken(APPLE_KEY, "https://evil.example", ProviderTestKeys.APPLE_CLIENT_ID, sub,
				soon(), Map.of()), null)
			.andExpect(status().isUnauthorized());
		login("APPLE", "not-a-jwt", null).andExpect(status().isUnauthorized());
		// 서명 대상이 아닌 형식(alg=none 흉내)도 거부
		String[] parts = stubs.appleIdToken(APPLE_KEY, sub, soon()).split("\\.");
		login("APPLE", parts[0] + "." + parts[1] + ".", null).andExpect(status().isUnauthorized());
	}

	/** 키 회전: 모르는 kid가 오면 JWKS를 다시 받아 새 키로 검증한다(캐시 수명 전이라도). */
	@Test
	void appleKeyRotationRefetchesJwks() throws Exception {
		stubs.appleKeys(APPLE_KEY);
		String sub = "001234." + UUID.randomUUID();
		login("APPLE", stubs.appleIdToken(APPLE_KEY, sub, soon()), null).andExpect(status().isOk());

		stubs.appleKeys(APPLE_KEY, APPLE_ROTATED_KEY);
		login("APPLE", stubs.appleIdToken(APPLE_ROTATED_KEY, sub, soon()), null).andExpect(status().isOk())
			.andExpect(jsonPath("$.registered").value(false));
	}

	/** 구글: 서명·iss(두 표기 모두)·aud ∈ GOOGLE_CLIENT_IDS·만료. 서버 인가 코드는 갱신 토큰으로 교환해 둔다. */
	@Test
	void googleLoginAcceptsBothIssuerSpellingsAndRejectsMismatches_01_13() throws Exception {
		stubs.googleKeys(GOOGLE_KEY);
		stubs.googleCodeExchange("google-code", "google-refresh-1");
		String sub = "1098" + kakaoId();
		login("GOOGLE", stubs.googleIdToken(GOOGLE_KEY, sub, soon()), "google-code").andExpect(status().isOk())
			.andExpect(jsonPath("$.registered").value(false));
		server.verify(postRequestedFor(urlPathEqualTo(ProviderStubs.GOOGLE_TOKEN)).withRequestBody(containing("code=google-code"))
			.withRequestBody(containing("client_secret=" + ProviderTestKeys.GOOGLE_SERVER_CLIENT_SECRET)));
		login("GOOGLE", ProviderTestKeys.idToken(GOOGLE_KEY, "accounts.google.com",
				ProviderTestKeys.GOOGLE_CLIENT_IDS.get(0), sub, soon(), Map.of()), null)
			.andExpect(status().isOk());

		login("GOOGLE", ProviderTestKeys.idToken(GOOGLE_KEY, ProviderTestKeys.GOOGLE_ISSUER, "someone-else", sub, soon(),
				Map.of()), null)
			.andExpect(status().isUnauthorized());
		login("GOOGLE", ProviderTestKeys.idToken(GOOGLE_KEY, "https://accounts.evil.example",
				ProviderTestKeys.GOOGLE_CLIENT_IDS.get(0), sub, soon(), Map.of()), null)
			.andExpect(status().isUnauthorized());
		login("GOOGLE", stubs.googleIdToken(GOOGLE_KEY, sub, clock.instant().minusSeconds(1)), null)
			.andExpect(status().isUnauthorized());
		login("GOOGLE", stubs.googleIdToken(APPLE_KEY, sub, soon()), null).andExpect(status().isUnauthorized());
	}

	/** BR-MEM-02: iOS는 카카오·애플·구글, Android는 카카오·구글. 개발 전용 DEV는 소셜 로그인 경로로 받지 않는다. */
	@Test
	void providerListFollowsPlatformRule() throws Exception {
		mvc.perform(get("/api/v1/auth/providers").param("platform", "IOS"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.providers").value(org.hamcrest.Matchers.contains("KAKAO", "APPLE", "GOOGLE")));
		mvc.perform(get("/api/v1/auth/providers").param("platform", "ANDROID"))
			.andExpect(jsonPath("$.providers").value(org.hamcrest.Matchers.contains("KAKAO", "GOOGLE")));
		login("DEV", "dev-someone", null).andExpect(status().isBadRequest());
	}

	private static String formParam(String body, String name) {
		for (String pair : body.split("&")) {
			int eq = pair.indexOf('=');
			if (eq > 0 && pair.substring(0, eq).equals(name)) {
				return java.net.URLDecoder.decode(pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
			}
		}
		throw new AssertionError("form param " + name + " 없음: " + body);
	}

}
