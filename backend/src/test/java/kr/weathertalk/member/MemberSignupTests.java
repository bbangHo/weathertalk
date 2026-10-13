package kr.weathertalk.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import kr.weathertalk.AuthTestClient;
import kr.weathertalk.IntegrationTest;
import kr.weathertalk.IntegrationTestConfiguration.ScriptedNicknameGenerator;
import kr.weathertalk.MutableClock;
import kr.weathertalk.shared.error.ErrorCode;

/**
 * 개발 전용 로그인 → 가입 티켓 → 가입 완료 (FR-MEM-02 흐름, FR-MEM-03, BR-MEM-01·06·07·08).
 */
@IntegrationTest
class MemberSignupTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	MutableClock clock;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ApplicationEvents events;

	@Autowired
	ScriptedNicknameGenerator nicknames;

	@Autowired
	MemberApi memberApi;

	AuthTestClient auth;

	@BeforeEach
	void setUp() {
		clock.reset();
		nicknames.clear();
		auth = new AuthTestClient(mvc);
	}

	/** legacy 01-#11: 로그인 결과는 타입 있는 불리언(registered)으로 신규/기존을 구분한다. 신규면 회원을 만들지 않는다. */
	@Test
	void legacy01_11_unknownAccountGetsSignupTicketAndNoMemberIsCreated() throws Exception {
		Integer before = jdbc.queryForObject("SELECT count(*) FROM member_member", Integer.class);

		auth.devLogin(AuthTestClient.newProviderUserId(), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.registered").value(false))
			.andExpect(jsonPath("$.signupTicket.ticket").isString())
			.andExpect(jsonPath("$.tokens").doesNotExist())
			.andExpect(jsonPath("$.terms.length()").value(2));

		assertThat(jdbc.queryForObject("SELECT count(*) FROM member_member", Integer.class)).isEqualTo(before);
	}

	@Test
	void signupCreatesMemberWithEverythingAtOnceAndPublishesMemberRegistered() throws Exception {
		String id = AuthTestClient.newProviderUserId();
		String json = auth.completeSignup(auth.signupTicket(id), auth.agreeAll(), "COLD_SENSITIVE")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.registered").value(true))
			.andExpect(jsonPath("$.member.nickname").isString())
			.andExpect(jsonPath("$.member.temperatureSensitivity").value("COLD_SENSITIVE"))
			.andExpect(jsonPath("$.member.termsReagreementRequired").value(false))
			.andReturn()
			.getResponse()
			.getContentAsString();
		AuthTestClient.Session session = AuthTestClient.session(json, id);

		assertThat(events.stream(MemberRegistered.class)).anyMatch(e -> e.memberId().equals(session.memberId()));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM member_terms_agreement WHERE member_id = ?", Integer.class,
				session.memberId().value())).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT agreed_at IS NOT NULL FROM member_terms_agreement WHERE member_id = ? LIMIT 1",
				Boolean.class, session.memberId().value())).isTrue();

		// 같은 로그인 계정으로 다시 로그인하면 바로 토큰
		auth.devLogin(id, null)
			.andExpect(jsonPath("$.registered").value(true))
			.andExpect(jsonPath("$.tokens.accessToken").isString())
			.andExpect(jsonPath("$.member.memberId").value(session.memberId().value().toString()));
	}

	/** legacy 01-#5: 필수 약관 동의를 서버가 검증한다 — 미동의·누락·구버전 3경우. */
	@Test
	void legacy01_5_signupIsRejectedWithoutRequiredTerms() throws Exception {
		String ticket = auth.signupTicket(AuthTestClient.newProviderUserId());
		List<Map<String, Object>> all = auth.agreeAll();

		List<Map<String, Object>> disagreed = new ArrayList<>();
		for (Map<String, Object> a : all) {
			Map<String, Object> copy = new HashMap<>(a);
			if ("PRIVACY".equals(a.get("kind"))) {
				copy.put("agreed", false);
			}
			disagreed.add(copy);
		}
		List<Map<String, Object>> missing = all.stream().filter(a -> !"PRIVACY".equals(a.get("kind"))).toList();
		List<Map<String, Object>> oldVersion = all.stream().map(a -> {
			Map<String, Object> copy = new HashMap<>(a);
			copy.put("version", ((Integer) a.get("version")) - 1);
			return (Map<String, Object>) copy;
		}).toList();

		for (List<Map<String, Object>> agreements : List.of(disagreed, missing, oldVersion)) {
			auth.completeSignup(ticket, agreements, "NORMAL")
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value(ErrorCode.REQUIRED_TERMS_NOT_AGREED.name()));
		}
		// 거부된 요청은 티켓을 쓰지 않는다: 같은 티켓으로 다시 가입할 수 있다.
		auth.completeSignup(ticket, all, "NORMAL").andExpect(status().isOk());
	}

	@Test
	void signupRequiresTemperatureSensitivity() throws Exception {
		auth.completeSignup(auth.signupTicket(AuthTestClient.newProviderUserId()), auth.agreeAll(), null)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.INVALID_REQUEST.name()));
	}

	@Test
	void expiredSignupTicketIsRejected() throws Exception {
		String ticket = auth.signupTicket(AuthTestClient.newProviderUserId());
		clock.advance(Duration.ofMinutes(30));

		auth.completeSignup(ticket, auth.agreeAll(), "NORMAL")
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.SIGNUP_TICKET_INVALID.name()));
		auth.completeSignup("forged-ticket", auth.agreeAll(), "NORMAL")
			.andExpect(jsonPath("$.code").value(ErrorCode.SIGNUP_TICKET_INVALID.name()));
	}

	/** FR-MEM-03 규칙 5: 같은 티켓 중복 탭·재시도는 새 회원을 만들지 않고 같은 회원으로 로그인한다. */
	@Test
	void duplicateSignupWithSameTicketCreatesOneMember() throws Exception {
		String id = AuthTestClient.newProviderUserId();
		String ticket = auth.signupTicket(id);
		List<Map<String, Object>> agreements = auth.agreeAll();

		ExecutorService pool = Executors.newFixedThreadPool(4);
		try {
			List<Callable<String>> calls = new ArrayList<>();
			for (int i = 0; i < 4; i++) {
				calls.add(() -> auth.completeSignup(ticket, agreements, "NORMAL").andReturn().getResponse()
					.getContentAsString());
			}
			List<String> memberIds = new ArrayList<>();
			for (Future<String> f : pool.invokeAll(calls)) {
				memberIds.add(JsonPath.read(f.get(), "$.member.memberId"));
			}
			assertThat(memberIds).hasSize(4).containsOnly(memberIds.getFirst());
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM member_login_account WHERE provider = 'DEV' AND provider_user_id = ?",
				Integer.class, id)).isEqualTo(1);
	}

	/** legacy 01-#13(이메일 부분), ADR 0006: 이메일은 선택이며 "null" 같은 대체 문자열로 저장하지 않는다. */
	@Test
	void legacy01_13_emailIsOptionalAndNeverStoredAsNullString() throws Exception {
		String withoutEmail = AuthTestClient.newProviderUserId();
		String nullString = AuthTestClient.newProviderUserId();
		String withEmail = AuthTestClient.newProviderUserId();

		AuthTestClient.Session a = auth.signup(withoutEmail);
		String ticket = JsonPath.read(auth.devLogin(nullString, "null").andReturn().getResponse().getContentAsString(),
				"$.signupTicket.ticket");
		AuthTestClient.Session b = AuthTestClient.session(auth.completeSignup(ticket, auth.agreeAll(), "NORMAL")
			.andReturn().getResponse().getContentAsString(), nullString);
		ticket = JsonPath.read(auth.devLogin(withEmail, "me@example.com").andReturn().getResponse().getContentAsString(),
				"$.signupTicket.ticket");
		AuthTestClient.Session c = AuthTestClient.session(auth.completeSignup(ticket, auth.agreeAll(), "NORMAL")
			.andReturn().getResponse().getContentAsString(), withEmail);

		assertThat(email(a)).isNull();
		assertThat(email(b)).isNull();
		assertThat(email(c)).isEqualTo("me@example.com");
		mvc.perform(get("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION, a.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.email").doesNotExist())
			.andExpect(jsonPath("$.providers[0]").value("DEV"));
	}

	/** legacy 01-#9, BR-MEM-08: 무작위 닉네임이 겹치면 다시 만든다. 한도를 넘으면 일시 오류이고 회원은 생기지 않는다. */
	@Test
	void legacy01_9_nicknameCollisionRegeneratesAndGivesUpAfterMaxAttempts() throws Exception {
		AuthTestClient.Session first = auth.signup();
		String taken = memberApi.findProfile(first.memberId()).orElseThrow().nickname();

		nicknames.enqueue(taken, taken, "겹치지않는닉1");
		String json = auth.completeSignup(auth.signupTicket(AuthTestClient.newProviderUserId()), auth.agreeAll(),
				"NORMAL").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat((String) JsonPath.read(json, "$.member.nickname")).isEqualTo("겹치지않는닉1");

		for (int i = 0; i < 10; i++) {
			nicknames.enqueue(taken);
		}
		String id = AuthTestClient.newProviderUserId();
		auth.completeSignup(auth.signupTicket(id), auth.agreeAll(), "NORMAL")
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value(ErrorCode.TEMPORARILY_UNAVAILABLE.name()));
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM member_login_account WHERE provider = 'DEV' AND provider_user_id = ?",
				Integer.class, id)).isZero();
	}

	@Test
	void devLoginRejectsMalformedIdAsAuthenticationFailure() throws Exception {
		auth.devLogin("공백 포함 아이디", null)
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.AUTHENTICATION_FAILED.name()));
	}

	private String email(AuthTestClient.Session session) {
		return jdbc.queryForObject("SELECT email FROM member_member WHERE id = ?", String.class,
				session.memberId().value());
	}

}
