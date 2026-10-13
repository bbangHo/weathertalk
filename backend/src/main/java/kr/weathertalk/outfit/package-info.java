/**
 * 옷 추천: 온도 체감 태그·강수 여부 → 옷차림. 다른 도메인 모듈에 의존하지 않는다. (docs/to-be/04-feeling.md 옷 추천 장)
 * <p>
 * 공개 API: 이 패키지의 타입. 구현은 {@code internal} 하위 패키지에 두며 다른 모듈에서 접근할 수 없다.
 * 계약 정본: docs/to-be/contracts.md §2.5
 */
@ApplicationModule(displayName = "outfit", allowedDependencies = { "shared" })
package kr.weathertalk.outfit;

import org.springframework.modulith.ApplicationModule;
