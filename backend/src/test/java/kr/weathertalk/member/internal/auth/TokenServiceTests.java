package kr.weathertalk.member.internal.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import kr.weathertalk.MutableClock;
import kr.weathertalk.member.internal.config.MemberProperties;
import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;

/**
 * 접근·갱신 토큰 구조 (01 §3.3, BR-MEM-04).
 */
class TokenServiceTests {

	static final String SECRET = "unit-test-token-secret-0123456789abcdef";

	MutableClock clock = new MutableClock();

	TokenService tokens = new TokenService(clock, properties(SECRET));

	UUID memberId = UUID.randomUUID();
	UUID sessionId = UUID.randomUUID();

	static MemberProperties properties(String secret) {
		return new MemberProperties(Duration.ofHours(1), Duration.ofDays(30), Duration.ofSeconds(30),
				Duration.ofMinutes(30), new MemberProperties.Nickname(10, Duration.ofDays(30), 2, 10),
				new MemberProperties.ExternalUnlink(8, Duration.ofMinutes(1), Duration.ofDays(30)),
				new MemberProperties.Token(secret));
	}

	@Test
	void issuedTokensCarryOnlyMemberSessionAndGeneration() {
		IssuedTokens issued = tokens.issue(memberId, sessionId, 3);

		assertThat(tokens.verifyAccess(issued.accessToken()))
			.isEqualTo(new TokenService.AccessClaims(memberId, sessionId));
		assertThat(tokens.verifyRefresh(issued.refreshToken()))
			.isEqualTo(new TokenService.RefreshClaims(memberId, sessionId, 3));
		String payload = new String(Base64.getUrlDecoder().decode(issued.accessToken().split("\\.")[1]));
		assertThat(payload).doesNotContain("email").doesNotContain("provider");
		assertThat(issued.accessTokenExpiresAt()).isEqualTo(MutableClock.BASE.plus(Duration.ofHours(1)));
		assertThat(issued.refreshTokenExpiresAt()).isEqualTo(MutableClock.BASE.plus(Duration.ofDays(30)));
	}

	/** legacy 01-#1: 갱신 토큰으로 보호 기능을 호출할 수 없고, 접근 토큰으로 재발급할 수 없다. */
	@Test
	void legacy01_1_tokenKindsAreNotInterchangeable() {
		IssuedTokens issued = tokens.issue(memberId, sessionId, 1);

		assertAuthFailed(() -> tokens.verifyAccess(issued.refreshToken()));
		assertAuthFailed(() -> tokens.verifyRefresh(issued.accessToken()));
		assertAuthFailed(() -> tokens.verifyAccessIgnoringExpiry(issued.refreshToken()));
	}

	@Test
	void expiredTokensAreRejectedButLogoutCanStillReadThem() {
		IssuedTokens issued = tokens.issue(memberId, sessionId, 1);

		clock.advance(Duration.ofHours(1));
		assertAuthFailed(() -> tokens.verifyAccess(issued.accessToken()));
		assertThat(tokens.verifyAccessIgnoringExpiry(issued.accessToken()).sessionId()).isEqualTo(sessionId);

		clock.advance(Duration.ofDays(30));
		assertAuthFailed(() -> tokens.verifyRefresh(issued.refreshToken()));
		assertThat(tokens.verifyRefreshIgnoringExpiry(issued.refreshToken()).generation()).isEqualTo(1);
	}

	@Test
	void tamperedOrForeignTokensAreRejected() {
		IssuedTokens issued = tokens.issue(memberId, sessionId, 1);
		String[] parts = issued.accessToken().split("\\.");
		String forgedPayload = Base64.getUrlEncoder()
			.withoutPadding()
			.encodeToString(new String(Base64.getUrlDecoder().decode(parts[1])).replace(memberId.toString(),
					UUID.randomUUID().toString())
				.getBytes());

		assertAuthFailed(() -> tokens.verifyAccess(parts[0] + "." + forgedPayload + "." + parts[2]));
		assertAuthFailed(() -> tokens.verifyAccess("not-a-jwt"));
		assertAuthFailed(() -> tokens.verifyAccess(""));

		TokenService otherKey = new TokenService(clock, properties("another-secret-0123456789abcdef0123456789"));
		assertAuthFailed(() -> otherKey.verifyAccess(issued.accessToken()));
	}

	private static void assertAuthFailed(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
		assertThatThrownBy(call).isInstanceOfSatisfying(DomainException.class,
				e -> assertThat(e.code()).isEqualTo(ErrorCode.AUTHENTICATION_FAILED));
	}

}
