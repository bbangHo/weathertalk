package kr.weathertalk.member.internal.domain;

/**
 * 로그인 계정 제공자 (BR-MEM-02). DEV는 개발 전용 로그인용 흉내 제공자로, local·staging·test에서만 검증기가 등록된다.
 */
public enum AuthProvider {

	KAKAO,

	APPLE,

	GOOGLE,

	DEV

}
