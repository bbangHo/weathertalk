package kr.weathertalk.member.internal.domain;

/**
 * 로그인 계정 제공자 (BR-MEM-02). DEV는 개발 전용 로그인용 흉내 제공자로, local·staging·test에서만 검증기가 등록된다.
 * TOSS_ANON은 앱인토스(토스 미니앱) 사용자 식별키 로그인이다. 토스 로그인(사업자 필요)을 붙이기 전까지 미니앱의 유일한 로그인 수단이다.
 */
public enum AuthProvider {

	KAKAO,

	APPLE,

	GOOGLE,

	TOSS_ANON,

	DEV

}
