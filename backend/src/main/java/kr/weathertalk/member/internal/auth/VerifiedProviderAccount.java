package kr.weathertalk.member.internal.auth;

import kr.weathertalk.member.internal.domain.AuthProvider;

/**
 * 검증을 통과한 로그인 계정 (ADR 0006).
 *
 * @param email 선택 정보. 없으면 null — "null" 같은 대체 문자열을 만들지 않는다.
 */
public record VerifiedProviderAccount(AuthProvider provider, String providerUserId, String email) {
}
