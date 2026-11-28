package kr.weathertalk.member.provider;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

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
import kr.weathertalk.member.MemberApi;
import kr.weathertalk.member.MemberStatus;

/**
 * 카카오 연결 해제 웹훅: 어드민 키·앱 ID로 진위를 확인하고, 우리 쪽 카카오 로그인 연결만 지운다(카카오 unlink 재호출·해제 작업 없음).
 * 마지막 로그인 계정이어도 탈퇴시키지 않는다. 모르는 사용자·중복 알림은 200.
 */
@IntegrationTest
class KakaoUnlinkWebhookTests {

	static final String PATH = "/api/v1/webhooks/kakao/unlink";

	static final String VALID_AUTH = "KakaoAK " + ProviderTestKeys.KAKAO_ADMIN_KEY;

	@Autowired
	MockMvc mvc;

	@Autowired
	WireMockServer server;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	MemberApi memberApi;

	ProviderStubs stubs;

	AuthTestClient auth;

	@BeforeEach
	void setUp() {
		server.resetAll();
		stubs = new ProviderStubs(server);
		auth = new AuthTestClient(mvc);
	}

	private static long newKakaoId() {
		return Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L);
	}

	private ResultActions webhookPost(String authorization, String appId, String userId) throws Exception {
		var request = post(PATH).contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.param("app_id", appId)
			.param("user_id", userId)
			.param("referrer_type", "UNLINK_FROM_APPS");
		return mvc.perform(authorization == null ? request : request.header(HttpHeaders.AUTHORIZATION, authorization));
	}

	private int kakaoAccounts(long kakaoId) {
		return jdbc.sql("SELECT count(*) FROM member_login_account WHERE provider = 'KAKAO' AND provider_user_id = :id")
			.param("id", Long.toString(kakaoId))
			.query(Integer.class)
			.single();
	}

	private int unlinkJobs(long kakaoId) {
		return jdbc.sql("SELECT count(*) FROM member_external_unlink WHERE provider = 'KAKAO' AND provider_user_id = :id")
			.param("id", Long.toString(kakaoId))
			.query(Integer.class)
			.single();
	}

	/** DEV 계정으로 가입하고 카카오 계정을 더한 회원(로그인 수단 둘). */
	private AuthTestClient.Session memberWithKakao(long kakaoId) throws Exception {
		AuthTestClient.Session me = auth.signup();
		String token = "wh-" + kakaoId;
		stubs.kakaoToken(token, kakaoId, ProviderTestKeys.KAKAO_APP_ID, null);
		mvc.perform(post("/api/v1/members/me/login-accounts").header(HttpHeaders.AUTHORIZATION, me.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"provider\":\"KAKAO\",\"token\":\"" + token + "\"}")).andExpect(status().isOk());
		return me;
	}

	@Test
	void removesKakaoLinkWithoutCallingKakaoAndIsIdempotent() throws Exception {
		long kakaoId = newKakaoId();
		AuthTestClient.Session me = memberWithKakao(kakaoId);
		server.resetRequests();

		webhookPost(VALID_AUTH, ProviderTestKeys.KAKAO_APP_ID, Long.toString(kakaoId)).andExpect(status().isOk());
		assertThat(kakaoAccounts(kakaoId)).isZero();
		mvc.perform(get("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION, me.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.providers").value(org.hamcrest.Matchers.contains("DEV")));

		// 같은 알림이 다시 와도(GET) 200
		mvc.perform(get(PATH).param("app_id", ProviderTestKeys.KAKAO_APP_ID)
			.param("user_id", Long.toString(kakaoId))
			.param("referrer_type", "UNLINK_FROM_APPS")
			.header(HttpHeaders.AUTHORIZATION, VALID_AUTH)).andExpect(status().isOk());

		assertThat(unlinkJobs(kakaoId)).isZero();
		server.verify(0, anyRequestedFor(urlPathEqualTo(ProviderStubs.KAKAO_UNLINK)));
	}

	/** 판단: 마지막 로그인 계정이어도 탈퇴시키지 않고 연결만 지운다(회원 데이터 보존). */
	@Test
	void lastLoginAccountIsRemovedButMemberStaysActive() throws Exception {
		long kakaoId = newKakaoId();
		String token = "only-" + kakaoId;
		stubs.kakaoToken(token, kakaoId, ProviderTestKeys.KAKAO_APP_ID, null);
		String login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"provider\":\"KAKAO\",\"token\":\"" + token + "\"}"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		String json = auth.completeSignup(JsonPath.read(login, "$.signupTicket.ticket"), auth.agreeAll(), "NORMAL")
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		AuthTestClient.Session me = AuthTestClient.session(json, null);

		webhookPost(VALID_AUTH, ProviderTestKeys.KAKAO_APP_ID, Long.toString(kakaoId)).andExpect(status().isOk());

		assertThat(kakaoAccounts(kakaoId)).isZero();
		assertThat(unlinkJobs(kakaoId)).isZero();
		assertThat(memberApi.findProfile(me.memberId()).orElseThrow().status()).isEqualTo(MemberStatus.ACTIVE);
	}

	@Test
	void unknownUserGets200() throws Exception {
		webhookPost(VALID_AUTH, ProviderTestKeys.KAKAO_APP_ID, Long.toString(newKakaoId())).andExpect(status().isOk());
	}

	@Test
	void wrongOrMissingAdminKeyIsRejected() throws Exception {
		long kakaoId = newKakaoId();
		memberWithKakao(kakaoId);

		webhookPost("KakaoAK wrong-key", ProviderTestKeys.KAKAO_APP_ID, Long.toString(kakaoId))
			.andExpect(status().isUnauthorized());
		webhookPost(null, ProviderTestKeys.KAKAO_APP_ID, Long.toString(kakaoId)).andExpect(status().isUnauthorized());

		assertThat(kakaoAccounts(kakaoId)).isOne();
	}

	@Test
	void wrongAppIdIsRejected() throws Exception {
		long kakaoId = newKakaoId();
		memberWithKakao(kakaoId);

		webhookPost(VALID_AUTH, "999999", Long.toString(kakaoId)).andExpect(status().isForbidden());

		assertThat(kakaoAccounts(kakaoId)).isOne();
	}

}
