/**
 * 회원: 회원, 로그인 계정, 토큰, 약관 동의, 온도 민감도, 탈퇴. (docs/to-be/01-member.md)
 * <p>
 * 공개 API: 이 패키지의 타입. 구현은 {@code internal} 하위 패키지에 두며 다른 모듈에서 접근할 수 없다.
 * 계약 정본: docs/to-be/contracts.md §2.1
 */
@ApplicationModule(displayName = "member", allowedDependencies = { "shared" })
package kr.weathertalk.member;

import org.springframework.modulith.ApplicationModule;
