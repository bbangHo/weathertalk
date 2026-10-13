package kr.weathertalk.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import kr.weathertalk.AuthTestClient;
import kr.weathertalk.IntegrationTest;
import kr.weathertalk.MutableClock;
import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;

/**
 * 현재 요청자 해석(FR-MEM-01), 토큰 재발급 회전·재사용 탐지(FR-MEM-04, BR-MEM-05), 로그아웃(FR-MEM-05), 관리자 여부(FR-MEM-13).
 */
@IntegrationTest
class MemberTokenLifecycleTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	MutableClock clock;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MemberApi memberApi;

	AuthTestClient auth;

	@BeforeEach
	void setUp() {
		clock.reset();
		auth = new AuthTestClient(mvc);
	}

	@Test
	void refreshRotatesRefreshTokenEveryTime() throws Exception {
		AuthTestClient.Session session = auth.signup();

		String first = refresh(session.refreshToken()).andExpect(status().isOk()).andReturn().getResponse()
			.getContentAsString();
		String rotated = JsonPath.read(first, "$.refreshToken");
		assertThat(rotated).isNotEqualTo(session.refreshToken());
		assertThat(generation(session)).isEqualTo(2);

		clock.advance(Duration.ofMinutes(5));
		String second = refresh(rotated).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(generation(session)).isEqualTo(3);
		mvc.perform(get("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION,
				"Bearer " + JsonPath.read(second, "$.accessToken"))).andExpect(status().isOk());
	}

	/** BR-MEM-05: 회전된 이전 세대 갱신 토큰이 다시 오면 탈취로 보고 세션 전체를 폐기한다. */
	@Test
	void reuseOfRotatedRefreshTokenRevokesTheWholeSession() throws Exception {
		AuthTestClient.Session session = auth.signup();
		String rotated = JsonPath.read(refresh(session.refreshToken()).andReturn().getResponse().getContentAsString(),
				"$.refreshToken");

		clock.advance(Duration.ofMinutes(1)); // 유예(30초) 경과
		refresh(session.refreshToken()).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.AUTHENTICATION_FAILED.name()));

		// 세션이 폐기되어 정상 회전된 최신 토큰과 기존 접근 토큰도 더는 쓸 수 없다.
		refresh(rotated).andExpect(status().isUnauthorized());
		me(session.bearer()).andExpect(status().isUnauthorized());
	}

	/** FR-MEM-04 규칙 6: 같은 세대의 중복 요청(네트워크 재시도)은 유예 안에서 같은 결과(같은 세대)를 돌려준다. */
	@Test
	void retryWithinGraceDoesNotLogTheMemberOut() throws Exception {
		AuthTestClient.Session session = auth.signup();
		refresh(session.refreshToken()).andExpect(status().isOk());

		clock.advance(Duration.ofSeconds(10));
		String retried = refresh(session.refreshToken()).andExpect(status().isOk()).andReturn().getResponse()
			.getContentAsString();

		assertThat(generation(session)).isEqualTo(2);
		refresh(JsonPath.read(retried, "$.refreshToken")).andExpect(status().isOk());
	}

	/** legacy 01-#1: 갱신 토큰으로 보호 기능을 호출하면 인증 실패, 접근 토큰으로 재발급해도 인증 실패. */
	@Test
	void legacy01_1_refreshTokenCannotCallProtectedApiAndViceVersa() throws Exception {
		AuthTestClient.Session session = auth.signup();

		me("Bearer " + session.refreshToken()).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.AUTHENTICATION_FAILED.name()));
		refresh(session.accessToken()).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.AUTHENTICATION_FAILED.name()));
	}

	/** FR-MEM-05: 로그아웃하면 그 세션의 접근·갱신 토큰이 즉시 거부된다. 다시 로그아웃해도 성공(멱등). */
	@Test
	void logoutRevokesSessionImmediately() throws Exception {
		AuthTestClient.Session session = auth.signup();
		AuthTestClient.Session otherDevice = AuthTestClient.session(
				auth.devLogin(session.providerUserId(), null).andReturn().getResponse().getContentAsString(),
				session.providerUserId());

		logout(session.refreshToken()).andExpect(status().isNoContent());

		me(session.bearer()).andExpect(status().isUnauthorized());
		refresh(session.refreshToken()).andExpect(status().isUnauthorized());
		logout(session.refreshToken()).andExpect(status().isNoContent());
		// 다른 로그인 세션(다른 기기)은 영향이 없다.
		me(otherDevice.bearer()).andExpect(status().isOk());

		mvc.perform(post("/api/v1/auth/logout").header(HttpHeaders.AUTHORIZATION, otherDevice.bearer()))
			.andExpect(status().isNoContent());
		me(otherDevice.bearer()).andExpect(status().isUnauthorized());
	}

	/** legacy 01-#17·#20: 탈퇴 회원의 토큰은 즉시 거부되고 재발급도 안 된다. */
	@Test
	void legacy01_17_20_withdrawnMembersTokensAreRejectedImmediately() throws Exception {
		AuthTestClient.Session session = auth.signup();
		me(session.bearer()).andExpect(status().isOk());

		mvc.perform(delete("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION, session.bearer()))
			.andExpect(status().isNoContent());

		me(session.bearer()).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.AUTHENTICATION_FAILED.name()));
		refresh(session.refreshToken()).andExpect(status().isUnauthorized());
		assertThatThrownBy(() -> memberApi.resolveRequester(session.accessToken()))
			.isInstanceOfSatisfying(DomainException.class,
					e -> assertThat(e.code()).isEqualTo(ErrorCode.AUTHENTICATION_FAILED));
	}

	/** legacy 01-#15: 인증 단계 실패는 모두 같은 형식(401 + code)으로. 토큰이 없으면 비회원 → 회원 전용은 가입 필요. */
	@ParameterizedTest
	@ValueSource(strings = { "Bearer not-a-token", "Bearer ", "Token abc", "Bearer eyJhbGciOiJIUzI1NiJ9.e30.x" })
	void legacy01_15_everyAuthenticationFailureIs401WithCode(String header) throws Exception {
		me(header).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.AUTHENTICATION_FAILED.name()));
	}

	@Test
	void legacy01_15_expiredAccessTokenAndGuestAreDistinguished() throws Exception {
		AuthTestClient.Session session = auth.signup();
		clock.advance(Duration.ofHours(1));

		me(session.bearer()).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.AUTHENTICATION_FAILED.name()));
		mvc.perform(get("/api/v1/members/me")).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.SIGNUP_REQUIRED.name()));
		// 갱신 토큰은 아직 유효하므로 재발급으로 복구된다.
		refresh(session.refreshToken()).andExpect(status().isOk());
	}

	/** FR-MEM-13: 관리자 지정·해제는 운영 절차(DB)로만 하고, 현재 요청자 해석에 즉시 반영된다. */
	@Test
	void adminRoleIsGrantedOnlyByOperationsAndReflectedImmediately() throws Exception {
		AuthTestClient.Session session = auth.signup();
		assertThat(member(session).admin()).isFalse();

		jdbc.update("UPDATE member_member SET role = 'ADMIN' WHERE id = ?", session.memberId().value());
		assertThat(member(session).admin()).isTrue();

		jdbc.update("UPDATE member_member SET role = 'USER' WHERE id = ?", session.memberId().value());
		assertThat(member(session).admin()).isFalse();
	}

	private Requester.Member member(AuthTestClient.Session session) {
		return (Requester.Member) memberApi.resolveRequester(session.accessToken());
	}

	private int generation(AuthTestClient.Session session) {
		return jdbc.queryForObject("SELECT max(generation) FROM member_login_session WHERE member_id = ?",
				Integer.class, session.memberId().value());
	}

	private ResultActions me(String authorization) throws Exception {
		return mvc.perform(get("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION, authorization));
	}

	private ResultActions refresh(String refreshToken) throws Exception {
		return mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
			.content("{\"refreshToken\":\"" + refreshToken + "\"}"));
	}

	private ResultActions logout(String refreshToken) throws Exception {
		return mvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON)
			.content("{\"refreshToken\":\"" + refreshToken + "\"}"));
	}

}
