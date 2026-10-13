package kr.weathertalk.member.internal.auth;

import kr.weathertalk.member.internal.domain.AuthProvider;

/**
 * 제공자 자격 검증 포트 (BR-MEM-03). 로그인 흐름에서 "제공자 토큰 검증" 단계만 맡는다.
 * <p>
 * 구현: 카카오(토큰 정보 + 앱 ID 일치), 애플·구글(JWKS 서명·iss·aud·만료), 개발 전용 DEV(local·staging·test).
 * 검증 실패(만료·위조·대상 불일치·제공자 거절)는 모두 AUTHENTICATION_FAILED, 제공자 장애·설정 누락은 TEMPORARILY_UNAVAILABLE로 던진다
 * (legacy 01-#12, FR-MEM-02 실패).
 */
public interface ProviderCredentialVerifier {

	AuthProvider provider();

	/** 이 제공자 로그인에 필요한 키가 설정돼 있는가(제공자 목록 조회에 반영). */
	default boolean available() {
		return true;
	}

	VerifiedProviderAccount verify(ProviderCredential credential);

}
