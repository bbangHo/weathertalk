package kr.weathertalk.member.internal.auth;

import java.time.Instant;

/** 접근 토큰 + 갱신 토큰 한 쌍. */
public record IssuedTokens(String accessToken, Instant accessTokenExpiresAt, String refreshToken,
		Instant refreshTokenExpiresAt) {

	@Override
	public String toString() {
		return "IssuedTokens[accessTokenExpiresAt=" + accessTokenExpiresAt + ", refreshTokenExpiresAt="
				+ refreshTokenExpiresAt + "]";
	}

}
