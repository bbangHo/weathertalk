/**
 * 홈: 메인 화면 조합. 읽기 전용이며 비즈니스 규칙을 두지 않는다. 어떤 모듈도 home을 의존하지 않는다. (docs/to-be/07-home-and-screens.md)
 * <p>
 * 공개 API: 이 패키지의 타입. 구현은 {@code internal} 하위 패키지에 두며 다른 모듈에서 접근할 수 없다.
 * 계약 정본: docs/to-be/contracts.md §1
 */
@ApplicationModule(displayName = "home", allowedDependencies = { "shared", "member", "neighborhood", "weather", "feeling", "outfit", "community", "notification" })
package kr.weathertalk.home;

import org.springframework.modulith.ApplicationModule;
