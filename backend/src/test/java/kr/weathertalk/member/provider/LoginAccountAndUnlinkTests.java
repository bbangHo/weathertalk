package kr.weathertalk.member.provider;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static kr.weathertalk.member.provider.ProviderTestKeys.APPLE_KEY;
import static kr.weathertalk.member.provider.ProviderTestKeys.GOOGLE_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.jayway.jsonpath.JsonPath;

import kr.weathertalk.AuthTestClient;
import kr.weathertalk.IntegrationTest;
import kr.weathertalk.MutableClock;
import kr.weathertalk.member.MemberApi;
import kr.weathertalk.member.MemberStatus;
import kr.weathertalk.member.internal.ExternalUnlinkService;

/**
 * 로그인 계정 연결·해제(FR-MEM-06·07)와 탈퇴 후 제공자 연결 해제(FR-MEM-09 규칙 3~5, BR-MEM-10).
 * 제공자 해제는 탈퇴 커밋 뒤 비동기로 돌고, 일시 오류는 백오프로 재시도, 끝내 실패해도 탈퇴는 그대로, 그사이 같은 계정이 새 회원에 연결되면 취소한다.
 */
@IntegrationTest
class LoginAccountAndUnlinkTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	MutableClock clock;

	@Autowired
	WireMockServer server;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	MemberApi memberApi;

	@Autowired
	ExternalUnlinkService unlinks;

	ProviderStubs stubs;

	AuthTestClient auth;

	@BeforeEach
	void setUp() {
		clock.reset();
		server.resetAll();
		stubs = new ProviderStubs(server);
		stubs.appleKeys(APPLE_KEY);
		stubs.googleKeys(GOOGLE_KEY);
		auth = new AuthTestClient(mvc);
	}

	private static long newKakaoId() {
		return Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L);
	}

	private ResultActions link(AuthTestClient.Session session, String provider, String token, String code)
			throws Exception {
		String body = "{\"provider\":\"" + provider + "\",\"token\":\"" + token + "\""
				+ (code == null ? "" : ",\"authorizationCode\":\"" + code + "\"") + "}";
		return mvc.perform(post("/api/v1/members/me/login-accounts").header(HttpHeaders.AUTHORIZATION, session.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private ResultActions unlink(AuthTestClient.Session session, String provider) throws Exception {
		return mvc.perform(delete("/api/v1/members/me/login-accounts/" + provider).header(HttpHeaders.AUTHORIZATION,
				session.bearer()));
	}

	private void withdraw(AuthTestClient.Session session) throws Exception {
		mvc.perform(delete("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION, session.bearer()))
			.andExpect(status().isNoContent());
	}

	/** 카카오 계정만으로 가입한 회원. */
	private AuthTestClient.Session kakaoMember(long kakaoId, String token) throws Exception {
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
		return AuthTestClient.session(json, null);
	}

	private Map<String, Object> job(String provider, String providerUserId) {
		return jdbc.sql("SELECT status, attempts, credential, last_error, next_attempt_at FROM member_external_unlink "
				+ "WHERE provider = :p AND provider_user_id = :id ORDER BY created_at DESC LIMIT 1")
			.param("p", provider)
			.param("id", providerUserId)
			.query()
			.singleRow();
	}

	private String jobStatus(String provider, String providerUserId) {
		return (String) job(provider, providerUserId).get("status");
	}

	private void kakaoUnlinkResponds(int status, String body) {
		server.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo(ProviderStubs.KAKAO_UNLINK))
			.willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)));
	}

	/** FR-MEM-06: 다른 제공자 계정을 더한다. 같은 제공자 두 번째·다른 회원이 쓰는 계정은 거부(병합 없음). */
	@Test
	void linkRejectsSecondAccountOfSameProviderAndAccountOwnedByAnotherMember() throws Exception {
		AuthTestClient.Session me = auth.signup();
		long kakaoId = newKakaoId();
		stubs.kakaoToken("link-me", kakaoId, ProviderTestKeys.KAKAO_APP_ID, null);
		link(me, "KAKAO", "link-me", null).andExpect(status().isOk())
			.andExpect(jsonPath("$.providers").value(org.hamcrest.Matchers.contains("KAKAO", "DEV")));

		stubs.kakaoToken("link-another", newKakaoId(), ProviderTestKeys.KAKAO_APP_ID, null);
		link(me, "KAKAO", "link-another", null).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("LOGIN_ACCOUNT_PROVIDER_ALREADY_LINKED"));

		AuthTestClient.Session other = auth.signup();
		link(other, "KAKAO", "link-me", null).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("LOGIN_ACCOUNT_IN_USE"));

		// 연결한 카카오 계정으로 로그인하면 같은 회원이다
		mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"provider\":\"KAKAO\",\"token\":\"link-me\"}"))
			.andExpect(jsonPath("$.registered").value(true))
			.andExpect(jsonPath("$.member.memberId").value(me.memberId().value().toString()));
	}

	/**
	 * FR-MEM-07: 마지막 로그인 계정은 해제 불가. 해제하면 커밋 후 비동기로 카카오 '연결 끊기'를 어드민 키 + 회원번호로 부른다 —
	 * 로그아웃 API가 아니다(legacy 01-#2 계약 확인).
	 */
	@Test
	void unlinkRejectsLastAccountAndCallsKakaoUnlinkNotLogout_01_2() throws Exception {
		long kakaoId = newKakaoId();
		AuthTestClient.Session me = kakaoMember(kakaoId, "only-kakao");
		unlink(me, "KAKAO").andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("LAST_LOGIN_ACCOUNT"));

		AuthTestClient.Session devMember = auth.signup();
		long secondId = newKakaoId();
		stubs.kakaoToken("second", secondId, ProviderTestKeys.KAKAO_APP_ID, null);
		link(devMember, "KAKAO", "second", null).andExpect(status().isOk());
		kakaoUnlinkResponds(200, "{\"id\":" + secondId + "}");
		unlink(devMember, "KAKAO").andExpect(status().isOk())
			.andExpect(jsonPath("$.providers").value(org.hamcrest.Matchers.contains("DEV")));

		await().atMost(Duration.ofSeconds(10))
			.untilAsserted(() -> assertThat(jobStatus("KAKAO", Long.toString(secondId))).isEqualTo("SUCCEEDED"));
		server.verify(1, postRequestedFor(urlPathEqualTo(ProviderStubs.KAKAO_UNLINK))
			.withHeader("Authorization", equalTo("KakaoAK " + ProviderTestKeys.KAKAO_ADMIN_KEY))
			.withRequestBody(containing("target_id_type=user_id"))
			.withRequestBody(containing("target_id=" + secondId)));
		assertThat(server.findAllUnmatchedRequests()).noneMatch(r -> r.getUrl().contains("logout"));
	}

	/**
	 * FR-MEM-09 규칙 1~5 (legacy 01-#3·#4): 탈퇴는 로컬 확정만 하고 즉시 응답, 커밋 뒤 계정마다 제공자 해제 — 카카오 unlink,
	 * 애플 갱신 토큰 철회(클라이언트 시크릿 서명), 구글 토큰 철회. 성공하면 보관 자격을 바로 파기한다.
	 */
	@Test
	void withdrawalUnlinksEveryProviderAfterCommit_01_3() throws Exception {
		long kakaoId = newKakaoId();
		AuthTestClient.Session me = kakaoMember(kakaoId, "wd-kakao");
		String appleSub = "001234." + UUID.randomUUID();
		stubs.appleCodeExchange("wd-apple-code", "apple-refresh-wd");
		link(me, "APPLE", stubs.appleIdToken(APPLE_KEY, appleSub, clock.instant().plusSeconds(600)), "wd-apple-code")
			.andExpect(status().isOk());
		String googleSub = "g-" + UUID.randomUUID();
		stubs.googleCodeExchange("wd-google-code", "google-refresh-wd");
		link(me, "GOOGLE", stubs.googleIdToken(GOOGLE_KEY, googleSub, clock.instant().plusSeconds(600)),
				"wd-google-code")
			.andExpect(status().isOk());
		kakaoUnlinkResponds(200, "{\"id\":" + kakaoId + "}");
		server.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo(ProviderStubs.APPLE_REVOKE)).willReturn(aResponse().withStatus(200)));
		server.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo(ProviderStubs.GOOGLE_REVOKE)).willReturn(okJson("{}")));

		withdraw(me);
		assertThat(memberApi.findProfile(me.memberId()).orElseThrow().status()).isEqualTo(MemberStatus.WITHDRAWN);

		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
			assertThat(jobStatus("KAKAO", Long.toString(kakaoId))).isEqualTo("SUCCEEDED");
			assertThat(jobStatus("APPLE", appleSub)).isEqualTo("SUCCEEDED");
			assertThat(jobStatus("GOOGLE", googleSub)).isEqualTo("SUCCEEDED");
		});
		server.verify(1, postRequestedFor(urlPathEqualTo(ProviderStubs.APPLE_REVOKE))
			.withRequestBody(containing("token=apple-refresh-wd"))
			.withRequestBody(containing("token_type_hint=refresh_token"))
			.withRequestBody(containing("client_secret=ey")));
		server.verify(1, postRequestedFor(urlPathEqualTo(ProviderStubs.GOOGLE_REVOKE))
			.withRequestBody(containing("token=google-refresh-wd")));
		assertThat(job("APPLE", appleSub).get("credential")).isNull();
		// 작업 기록에 회원 ID를 남기지 않는다
		assertThat(jdbc.sql("SELECT count(*) FROM information_schema.columns WHERE table_name = 'member_external_unlink' "
				+ "AND column_name = 'member_id'").query(Integer.class).single()).isZero();
	}

	/** BR-MEM-10: 일시 오류는 backoff(1분)부터 두 배씩 미뤄 재시도, max-attempts(8)를 다 쓰면 실패로 남기고 탈퇴는 되돌리지 않는다. */
	@Test
	void transientFailuresRetryWithBackoffThenFailWithoutUndoingWithdrawal() throws Exception {
		long kakaoId = newKakaoId();
		AuthTestClient.Session me = kakaoMember(kakaoId, "retry-kakao");
		kakaoUnlinkResponds(503, "{}");
		Instant withdrawnAt = clock.instant();
		withdraw(me);
		String id = Long.toString(kakaoId);
		await().atMost(Duration.ofSeconds(10))
			.untilAsserted(() -> assertThat(((Number) job("KAKAO", id).get("attempts")).intValue()).isEqualTo(1));
		assertThat(((java.sql.Timestamp) job("KAKAO", id).get("next_attempt_at")).toInstant())
			.isEqualTo(withdrawnAt.plus(Duration.ofMinutes(1)));

		// 아직 때가 아니면 부르지 않는다
		unlinks.processDue();
		assertThat(((Number) job("KAKAO", id).get("attempts")).intValue()).isEqualTo(1);

		clock.advance(Duration.ofMinutes(1));
		unlinks.processDue();
		Map<String, Object> second = job("KAKAO", id);
		assertThat(((Number) second.get("attempts")).intValue()).isEqualTo(2);
		assertThat(((java.sql.Timestamp) second.get("next_attempt_at")).toInstant())
			.isEqualTo(clock.instant().plus(Duration.ofMinutes(2)));

		for (int attempt = 3; attempt <= 8; attempt++) {
			clock.advance(Duration.ofMinutes(1L << (attempt - 2)));
			unlinks.processDue();
		}
		Map<String, Object> finalJob = job("KAKAO", id);
		assertThat(finalJob.get("status")).isEqualTo("FAILED");
		assertThat(((Number) finalJob.get("attempts")).intValue()).isEqualTo(8);
		assertThat((String) finalJob.get("last_error")).contains("503");
		assertThat(memberApi.findProfile(me.memberId()).orElseThrow().status()).isEqualTo(MemberStatus.WITHDRAWN);
		server.verify(8, postRequestedFor(urlPathEqualTo(ProviderStubs.KAKAO_UNLINK)));
	}

	/** FR-MEM-09 규칙 3: 실행 직전 같은 로그인 계정이 새 회원에 연결돼 있으면 해제를 취소한다(새 회원의 연결을 끊지 않게). */
	@Test
	void unlinkIsCancelledWhenSameAccountRejoinedBeforeRetry() throws Exception {
		long kakaoId = newKakaoId();
		AuthTestClient.Session me = kakaoMember(kakaoId, "rejoin-kakao");
		kakaoUnlinkResponds(503, "{}");
		withdraw(me);
		String id = Long.toString(kakaoId);
		await().atMost(Duration.ofSeconds(10))
			.untilAsserted(() -> assertThat(((Number) job("KAKAO", id).get("attempts")).intValue()).isEqualTo(1));

		AuthTestClient.Session rejoined = kakaoMember(kakaoId, "rejoin-kakao");
		assertThat(rejoined.memberId()).isNotEqualTo(me.memberId());
		kakaoUnlinkResponds(200, "{\"id\":" + kakaoId + "}");
		clock.advance(Duration.ofMinutes(1));
		unlinks.processDue();

		assertThat(jobStatus("KAKAO", id)).isEqualTo("CANCELLED");
		server.verify(1, postRequestedFor(urlPathEqualTo(ProviderStubs.KAKAO_UNLINK)));
	}

	/**
	 * legacy 01-#4: 애플 계정인데 보관한 자격이 없으면(인가 코드 없이 로그인) 빈 자격으로 철회를 부르지 않고 '생략'으로 남긴다.
	 * 카카오가 '이미 연결 끊김'(-101)으로 답하면 성공으로 본다.
	 */
	@Test
	void missingCredentialIsSkippedAndAlreadyUnlinkedCountsAsSuccess_01_4() throws Exception {
		long kakaoId = newKakaoId();
		AuthTestClient.Session me = kakaoMember(kakaoId, "skip-kakao");
		String appleSub = "001234." + UUID.randomUUID();
		link(me, "APPLE", stubs.appleIdToken(APPLE_KEY, appleSub, clock.instant().plusSeconds(600)), null)
			.andExpect(status().isOk());
		kakaoUnlinkResponds(400, "{\"msg\":\"NotRegisteredUserException\",\"code\":-101}");

		withdraw(me);
		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
			assertThat(jobStatus("KAKAO", Long.toString(kakaoId))).isEqualTo("SUCCEEDED");
			assertThat(jobStatus("APPLE", appleSub)).isEqualTo("SKIPPED");
		});
		assertThat(job("APPLE", appleSub).get("last_error")).isEqualTo("NO_CREDENTIAL");
		server.verify(0, postRequestedFor(urlPathEqualTo(ProviderStubs.APPLE_REVOKE)));
		assertThat(List.of(jobStatus("KAKAO", Long.toString(kakaoId)))).containsExactly("SUCCEEDED");
	}

}
