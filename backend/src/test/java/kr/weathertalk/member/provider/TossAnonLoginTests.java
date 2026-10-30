package kr.weathertalk.member.provider;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.jayway.jsonpath.JsonPath;

import kr.weathertalk.AuthTestClient;
import kr.weathertalk.IntegrationTest;

/**
 * 앱인토스 사용자 식별키 로그인. 앱이 낸 인증 코드를 서버가 교환 API(mTLS)로 바꿔 식별키를 회원 식별값으로 쓴다.
 * 교환 API는 WireMock으로 대신한다(인증서 적재는 테스트 전용 PEM으로 확인).
 */
@IntegrationTest
class TossAnonLoginTests {

	/** application-test.yml의 shared.cors.allowed-origins */
	private static final String MINIAPP_ORIGIN = "https://weathertalk.apps.tossmini.com";

	@Autowired
	MockMvc mvc;

	@Autowired
	WireMockServer server;

	@Autowired
	JdbcClient jdbc;

	ProviderStubs stubs;

	@BeforeEach
	void setUp() {
		server.resetAll();
		stubs = new ProviderStubs(server);
	}

	private ResultActions login(String code) throws Exception {
		return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"provider\":\"TOSS_ANON\",\"token\":\"" + code + "\"}"));
	}

	private static String anonKey() {
		return "anon-" + UUID.randomUUID();
	}

	/** 처음이면 가입 티켓 → 가입 → 같은 식별키의 새 코드로 다시 들어오면 같은 회원. 이메일은 없다. */
	@Test
	void exchangesCodeForAnonKeyThenSignsUpAndLogsInAsSameMember() throws Exception {
		String key = anonKey();
		stubs.tossAnonExchange("code-1", key);

		String json = login("code-1").andExpect(status().isOk())
			.andExpect(jsonPath("$.registered").value(false))
			.andReturn()
			.getResponse()
			.getContentAsString();
		server.verify(postRequestedFor(urlPathEqualTo(ProviderStubs.TOSS_ANON_EXCHANGE))
			.withRequestBody(equalToJson("{\"code\":\"code-1\"}")));
		AuthTestClient auth = new AuthTestClient(mvc);
		String signedUp = auth.completeSignup(JsonPath.read(json, "$.signupTicket.ticket"), auth.agreeAll(), "NORMAL")
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		String memberId = JsonPath.read(signedUp, "$.member.memberId");

		stubs.tossAnonExchange("code-2", key);
		login("code-2").andExpect(status().isOk())
			.andExpect(jsonPath("$.registered").value(true))
			.andExpect(jsonPath("$.member.memberId").value(memberId));
		assertThat(jdbc.sql("SELECT provider || ':' || provider_user_id FROM member_login_account WHERE member_id = :id")
			.param("id", UUID.fromString(memberId))
			.query(String.class)
			.single()).isEqualTo("TOSS_ANON:" + key);
	}

	/** 만료·재사용·위조 코드(4011)는 HTTP 200이어도 인증 실패. */
	@Test
	void invalidCodeIsAuthenticationFailure() throws Exception {
		stubs.tossAnonExchangeFails("used-code", "4011");
		login("used-code").andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));
	}

	/** 요청 한도 초과(4095)·교환 서버 장애는 잠시 뒤 다시 시도할 일시 오류. */
	@Test
	void rateLimitAndServerErrorAreTemporary() throws Exception {
		stubs.tossAnonExchangeFails("busy-code", "4095");
		login("busy-code").andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("TEMPORARILY_UNAVAILABLE"));

		server.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo(ProviderStubs.TOSS_ANON_EXCHANGE))
			.willReturn(aResponse().withStatus(500)));
		login("any-code").andExpect(status().isServiceUnavailable());
	}

	/** 앱인토스 빌드는 식별키만, 스토어 앱 목록에는 식별키가 끼지 않는다. */
	@Test
	void tossPlatformExposesOnlyAnonKeyLogin() throws Exception {
		mvc.perform(get("/api/v1/auth/providers").param("platform", "TOSS"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.providers").value(Matchers.contains("TOSS_ANON")));
		mvc.perform(get("/api/v1/auth/providers").param("platform", "ANDROID"))
			.andExpect(jsonPath("$.providers").value(Matchers.not(Matchers.hasItem("TOSS_ANON"))));
	}

	/** 미니앱 출처의 브라우저 사전 요청(preflight)은 허용, 모르는 출처는 거부. */
	@Test
	void corsAllowsOnlyConfiguredMiniAppOrigin() throws Exception {
		mvc.perform(options("/api/v1/auth/login").header(HttpHeaders.ORIGIN, MINIAPP_ORIGIN)
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type,idempotency-key"))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, MINIAPP_ORIGIN));
		mvc.perform(options("/api/v1/auth/login").header(HttpHeaders.ORIGIN, "https://evil.example")
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
			.andExpect(status().isForbidden());
	}

}
