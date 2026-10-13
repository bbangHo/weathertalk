package kr.weathertalk.member.internal.auth;

import kr.weathertalk.member.internal.domain.AuthProvider;

/**
 * 검증을 통과한 로그인 계정 (ADR 0006).
 *
 * @param email 선택 정보. 없으면 null — "null" 같은 대체 문자열을 만들지 않는다.
 * @param unlinkCredential 제공자 연결 해제용 자격 원문(애플·구글 갱신 토큰, FR-MEM-02 규칙 2). 이번 로그인에서 얻지 못했으면 null.
 *        저장 전에 {@link ProviderCredentialCipher}로 암호화한다.
 */
public record VerifiedProviderAccount(AuthProvider provider, String providerUserId, String email,
		String unlinkCredential) {

	public VerifiedProviderAccount(AuthProvider provider, String providerUserId, String email) {
		this(provider, providerUserId, email, null);
	}

	@Override
	public String toString() {
		return "VerifiedProviderAccount[provider=" + provider + ", providerUserId=" + providerUserId + "]";
	}

}
