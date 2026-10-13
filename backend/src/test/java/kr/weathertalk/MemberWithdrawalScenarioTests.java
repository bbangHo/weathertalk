package kr.weathertalk;

import static kr.weathertalk.neighborhood.NeighborhoodTestData.INSIDE_N1;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N2;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N3;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.web.servlet.MockMvc;

import kr.weathertalk.IntegrationTestConfiguration.ScriptedNicknameGenerator;
import kr.weathertalk.member.MemberApi;
import kr.weathertalk.member.MemberStatus;
import kr.weathertalk.member.MemberWithdrawn;
import kr.weathertalk.neighborhood.NeighborhoodApi;
import kr.weathertalk.neighborhood.NeighborhoodTestData;
import kr.weathertalk.neighborhood.internal.dataset.NeighborhoodDatasetLoader;

/**
 * 탈퇴 → 회원탈퇴함 → 동네 정리 (FR-MEM-09 로컬 확정, 02 §6 구독, legacy 01-#3).
 * 탈퇴는 로컬 확정 트랜잭션에서 이벤트 발행 저장소에 영속화되고, neighborhood는 커밋 후 비동기로 현재·관심 동네를 지운다.
 */
@IntegrationTest
class MemberWithdrawalScenarioTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	MemberApi memberApi;

	@Autowired
	NeighborhoodApi neighborhoodApi;

	@Autowired
	NeighborhoodDatasetLoader loader;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MutableClock clock;

	@Autowired
	ScriptedNicknameGenerator nicknames;

	@BeforeEach
	void setUp() {
		clock.reset();
		nicknames.clear();
		NeighborhoodTestData.loadBase(loader);
	}

	@Test
	void legacy01_3_withdrawalPublishesMemberWithdrawnAndNeighborhoodCleansUp(Scenario scenario) throws Exception {
		AuthTestClient auth = new AuthTestClient(mvc);
		AuthTestClient.Session session = auth.signup();
		String nickname = memberApi.findProfile(session.memberId()).orElseThrow().nickname();
		neighborhoodApi.confirmCurrentNeighborhood(session.memberId(), INSIDE_N1).orElseThrow();
		neighborhoodApi.mergeInterestNeighborhoods(session.memberId(), List.of(N2, N3));

		scenario.stimulate(() -> withdraw(session))
			.andWaitAtMost(Duration.ofSeconds(10))
			.andWaitForStateChange(() -> neighborhoodRows(session), rows -> rows == 0)
			.andVerify(rows -> {
				assertThat(neighborhoodApi.findCurrentNeighborhood(session.memberId())).isEmpty();
				assertThat(neighborhoodApi.findInterestNeighborhoods(session.memberId())).isEmpty();
			});

		// 로컬 확정: 상태 탈퇴, 개인정보 제거, 세션 폐기, 로그인 계정 연결 제거
		assertThat(memberApi.findProfile(session.memberId()).orElseThrow().status()).isEqualTo(MemberStatus.WITHDRAWN);
		assertThat(memberApi.findProfile(session.memberId()).orElseThrow().nickname()).isNull();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM member_login_session WHERE member_id = ? AND status = 'ACTIVE'",
				Integer.class, session.memberId().value())).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM member_login_account WHERE member_id = ?", Integer.class,
				session.memberId().value())).isZero();

		// 이벤트 발행 저장소에 영속화되고, 구독 처리가 끝나면 완료로 기록된다.
		org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM event_publication WHERE event_type = ? AND serialized_event LIKE ? "
						+ "AND completion_date IS NOT NULL",
				Integer.class, MemberWithdrawn.class.getName(), "%" + session.memberId().value() + "%")).isEqualTo(1));

		// 같은 제공자 계정으로 즉시 새 가입이 가능하고(재가입 유예 없음), 탈퇴 회원의 닉네임은 바로 해제된다.
		nicknames.enqueue(nickname);
		auth.devLogin(session.providerUserId(), null).andExpect(jsonPath("$.registered").value(false));
		AuthTestClient.Session rejoined = auth.signup(session.providerUserId());
		assertThat(rejoined.memberId()).isNotEqualTo(session.memberId());
		assertThat(memberApi.findProfile(rejoined.memberId()).orElseThrow().nickname()).isEqualTo(nickname);
	}

	@Test
	void withdrawalIsAllowedBeforeTermsReagreementAndOnlyOnce() throws Exception {
		AuthTestClient.Session session = new AuthTestClient(mvc).signup();
		jdbc.update("INSERT INTO member_terms (kind, version, title, required, major_revision, effective_at, content_url) "
				+ "VALUES ('SERVICE', 99, 'v99', true, true, '2026-01-02 00:00:00+09', 'about:blank')");
		try {
			mvc.perform(delete("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION, session.bearer()))
				.andExpect(status().isNoContent());
			mvc.perform(delete("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION, session.bearer()))
				.andExpect(status().isUnauthorized());
		}
		finally {
			jdbc.update("DELETE FROM member_terms WHERE version = 99");
		}
	}

	private int neighborhoodRows(AuthTestClient.Session session) {
		return jdbc.queryForObject("SELECT (SELECT count(*) FROM nbr_current_neighborhood WHERE member_id = ?) "
				+ "+ (SELECT count(*) FROM nbr_interest_neighborhood WHERE member_id = ?)", Integer.class,
				session.memberId().value(), session.memberId().value());
	}

	private void withdraw(AuthTestClient.Session session) {
		try {
			String body = mvc.perform(delete("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION, session.bearer()))
				.andExpect(status().isNoContent())
				.andReturn()
				.getResponse()
				.getContentAsString();
			assertThat(body).isEmpty();
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

}
