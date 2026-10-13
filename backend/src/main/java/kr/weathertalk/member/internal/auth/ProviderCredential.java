package kr.weathertalk.member.internal.auth;

import kr.weathertalk.member.internal.domain.AuthProvider;

/**
 * 앱이 로그인 때 내는 제공자 자격 (FR-MEM-02 입력).
 *
 * @param token 제공자 토큰(카카오 접근 토큰, 애플 identity token, 구글 ID 토큰). DEV는 임의 고유 ID.
 * @param authorizationCode 애플·구글 인가 코드(연결 해제용 자격 획득, M7). 없을 수 있다.
 * @param claimedEmail 개발 전용 로그인에서만 쓰는 흉내 이메일. 실제 제공자 검증기는 무시하고 검증된 토큰에서 이메일을 꺼낸다.
 */
public record ProviderCredential(AuthProvider provider, String token, String authorizationCode, String claimedEmail) {

	@Override
	public String toString() {
		return "ProviderCredential[provider=" + provider + "]";
	}

}
