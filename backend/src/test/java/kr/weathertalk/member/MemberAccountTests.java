package kr.weathertalk.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import kr.weathertalk.AuthTestClient;
import kr.weathertalk.IntegrationTest;
import kr.weathertalk.MutableClock;
import kr.weathertalk.shared.Ids.MemberId;
import kr.weathertalk.shared.error.ErrorCode;

/**
 * 프로필 조회(FR-MEM-10), 닉네임 변경(FR-MEM-11), 온도 민감도 변경(FR-MEM-08), 약관 재동의(FR-MEM-12).
 */
@IntegrationTest
class MemberAccountTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	MutableClock clock;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ApplicationEvents events;

	@Autowired
	MemberApi memberApi;

	AuthTestClient auth;

	@BeforeEach
	void setUp() {
		clock.reset();
		auth = new AuthTestClient(mvc);
	}

	@AfterEach
	void removeRevisedTerms() {
		jdbc.update("DELETE FROM member_terms_agreement WHERE version > 1");
		jdbc.update("DELETE FROM member_terms WHERE version > 1");
	}

	/** legacy 07-#19, BR-MEM-15: 30일 1회, 중복 불가, 길이·문자 규칙. 거부 사유를 그대로 돌려준다. */
	@Test
	void nicknameChangeRules() throws Exception {
		AuthTestClient.Session session = auth.signup();
		AuthTestClient.Session other = auth.signup();
		String othersNickname = memberApi.findProfile(other.memberId()).orElseThrow().nickname();

		changeNickname(session, "a").andExpect(status().isBadRequest());
		changeNickname(session, "열한글자가넘는닉네임이다").andExpect(status().isBadRequest());
		changeNickname(session, "특수!문자").andExpect(status().isBadRequest());
		changeNickname(session, othersNickname).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(ErrorCode.NICKNAME_TAKEN.name()));

		changeNickname(session, "  맑은하늘" + (System.nanoTime() % 1000) + " ").andExpect(status().isOk())
			.andExpect(jsonPath("$.nextChangeAvailableAt").value(MutableClock.BASE.plus(Duration.ofDays(30)).toString()));

		clock.advance(Duration.ofDays(29));
		session = relogin(session); // 접근 토큰 수명(1시간)이 지났으므로 다시 로그인
		changeNickname(session, "새닉네임" + (System.nanoTime() % 1000)).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value(ErrorCode.NICKNAME_CHANGE_TOO_SOON.name()))
			.andExpect(jsonPath("$.nextChangeAvailableAt").value(MutableClock.BASE.plus(Duration.ofDays(30)).toString()));

		clock.advance(Duration.ofDays(1));
		session = relogin(session);
		changeNickname(session,"새닉네임" + (System.nanoTime() % 1000)).andExpect(status().isOk());
	}

	/** FR-MEM-08: 바뀔 때만 온도민감도변경됨 발행(출처 포함). 같은 값이면 아무 일도 없다. */
	@Test
	void sensitivityChangePublishesEventOnlyWhenChanged() throws Exception {
		AuthTestClient.Session session = auth.signup();

		changeSensitivity(session, "{\"temperatureSensitivity\":\"NORMAL\"}").andExpect(status().isOk());
		assertThat(events.stream(TemperatureSensitivityChanged.class)).noneMatch(e -> e.memberId().equals(session.memberId()));

		changeSensitivity(session, "{\"temperatureSensitivity\":\"HEAT_SENSITIVE\",\"source\":\"CALIBRATION_ACCEPTED\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.temperatureSensitivity").value("HEAT_SENSITIVE"));
		assertThat(events.stream(TemperatureSensitivityChanged.class).filter(e -> e.memberId().equals(session.memberId())))
			.singleElement()
			.satisfies(e -> {
				assertThat(e.previous()).isEqualTo(TemperatureSensitivity.NORMAL);
				assertThat(e.current()).isEqualTo(TemperatureSensitivity.HEAT_SENSITIVE);
				assertThat(e.source()).isEqualTo(SensitivityChangeSource.CALIBRATION_ACCEPTED);
			});

		changeSensitivity(session, "{\"temperatureSensitivity\":\"뜨거움\"}").andExpect(status().isBadRequest());
	}

	@Test
	void profilesInBulkIncludeWithdrawnMembersWithoutNickname() throws Exception {
		AuthTestClient.Session active = auth.signup();
		AuthTestClient.Session withdrawn = auth.signup();
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/members/me")
			.header(HttpHeaders.AUTHORIZATION, withdrawn.bearer())).andExpect(status().isNoContent());

		Map<MemberId, MemberProfile> profiles = memberApi
			.findProfiles(List.of(active.memberId(), withdrawn.memberId(), MemberId.random()));

		assertThat(profiles).hasSize(2);
		assertThat(profiles.get(active.memberId()).status()).isEqualTo(MemberStatus.ACTIVE);
		assertThat(profiles.get(active.memberId()).nickname()).isNotBlank();
		assertThat(profiles.get(withdrawn.memberId()).withdrawn()).isTrue();
		assertThat(profiles.get(withdrawn.memberId()).nickname()).isNull();
	}

	/**
	 * legacy 01-#6(버전·재동의), FR-MEM-12, BR-MEM-16: 필수 약관이 중대 개정되면 재동의 전까지 '약관 재동의 필요'.
	 * 조회·민감도 변경은 계속 되고, 구버전 재제출은 거부, 현재 버전 동의 후 즉시 해제된다.
	 */
	@Test
	void legacy01_6_majorRevisionRequiresReagreementUntilAgreed() throws Exception {
		AuthTestClient.Session session = auth.signup();
		insertTerms("SERVICE", 2, true);

		assertThat(requester(session).termsReagreementRequired()).isTrue();
		mvc.perform(get("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION, session.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.termsReagreementRequired").value(true))
			.andExpect(jsonPath("$.termsToReagree[0].kind").value("SERVICE"))
			.andExpect(jsonPath("$.termsToReagree[0].version").value(2));
		changeSensitivity(session, "{\"temperatureSensitivity\":\"COLD_SENSITIVE\"}").andExpect(status().isOk());

		reagree(session, "[{\"kind\":\"SERVICE\",\"version\":1,\"agreed\":true}]")
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value(ErrorCode.REQUIRED_TERMS_NOT_AGREED.name()));
		assertThat(requester(session).termsReagreementRequired()).isTrue();

		reagree(session, "[{\"kind\":\"SERVICE\",\"version\":2,\"agreed\":true}]").andExpect(status().isOk())
			.andExpect(jsonPath("$.termsReagreementRequired").value(false));
		assertThat(requester(session).termsReagreementRequired()).isFalse();

		// legacy 01-#6: 같은 버전 재제출은 새 이력을 만들지 않는다((회원, 종류, 버전)당 1건).
		reagree(session, "[{\"kind\":\"SERVICE\",\"version\":2,\"agreed\":true}]").andExpect(status().isOk());
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM member_terms_agreement WHERE member_id = ? AND kind = 'SERVICE' AND version = 2",
				Integer.class, session.memberId().value())).isEqualTo(1);
	}

	@Test
	void minorRevisionDoesNotRequireReagreementButNewSignupsAgreeToIt() throws Exception {
		AuthTestClient.Session existing = auth.signup();
		insertTerms("PRIVACY", 2, false);

		assertThat(requester(existing).termsReagreementRequired()).isFalse();
		// 새 가입자는 현재 시행 버전(v2)에 동의해야 한다.
		String ticket = auth.signupTicket(AuthTestClient.newProviderUserId());
		auth.completeSignup(ticket, List.of(Map.of("kind", "SERVICE", "version", 1, "agreed", true),
				Map.of("kind", "PRIVACY", "version", 1, "agreed", true)), "NORMAL")
			.andExpect(jsonPath("$.code").value(ErrorCode.REQUIRED_TERMS_NOT_AGREED.name()));
		auth.completeSignup(ticket, auth.agreeAll(), "NORMAL").andExpect(status().isOk());
	}

	private void insertTerms(String kind, int version, boolean major) {
		jdbc.update("INSERT INTO member_terms (kind, version, title, required, major_revision, effective_at, content_url) "
				+ "VALUES (?, ?, ?, true, ?, ?, 'about:blank')", kind, version, kind + " v" + version, major,
				Timestamp.from(clock.instant()));
	}

	private AuthTestClient.Session relogin(AuthTestClient.Session session) throws Exception {
		return AuthTestClient.session(
				auth.devLogin(session.providerUserId(), null).andReturn().getResponse().getContentAsString(),
				session.providerUserId());
	}

	private Requester.Member requester(AuthTestClient.Session session) {
		return (Requester.Member) memberApi.resolveRequester(session.accessToken());
	}

	private ResultActions changeNickname(AuthTestClient.Session session, String nickname) throws Exception {
		return mvc.perform(put("/api/v1/members/me/nickname").header(HttpHeaders.AUTHORIZATION, session.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"nickname\":\"" + nickname + "\"}"));
	}

	private ResultActions changeSensitivity(AuthTestClient.Session session, String body) throws Exception {
		return mvc.perform(put("/api/v1/members/me/temperature-sensitivity")
			.header(HttpHeaders.AUTHORIZATION, session.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private ResultActions reagree(AuthTestClient.Session session, String agreements) throws Exception {
		return mvc.perform(post("/api/v1/members/me/terms-agreements").header(HttpHeaders.AUTHORIZATION, session.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"agreements\":" + agreements + "}"));
	}

}
