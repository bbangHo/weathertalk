package kr.weathertalk.member.internal.auth;

import kr.weathertalk.member.internal.domain.AuthProvider;

/**
 * 제공자 자격 검증 포트 (BR-MEM-03). 로그인 흐름에서 "제공자 토큰 검증" 단계만 맡는다.
 * <p>
 * 지금은 개발 전용 DEV 구현만 있다. M7에서 카카오(앱 ID 일치)·애플·구글(서명·iss·aud·만료) 구현을 추가하면
 * 로그인·가입 흐름은 그대로 동작한다. 검증 실패(만료·위조·제공자 오류)는 모두 AUTHENTICATION_FAILED로 던진다(legacy 01-#12).
 */
public interface ProviderCredentialVerifier {

	AuthProvider provider();

	VerifiedProviderAccount verify(ProviderCredential credential);

}
