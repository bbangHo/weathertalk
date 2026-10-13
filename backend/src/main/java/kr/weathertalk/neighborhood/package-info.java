/**
 * 동네: 행정동, 좌표→동네 판정, 예보 격자, 현재·관심 동네, 활성 동네. (docs/to-be/02-neighborhood.md)
 * <p>
 * 공개 API: 이 패키지의 타입. 구현은 {@code internal} 하위 패키지에 두며 다른 모듈에서 접근할 수 없다.
 * 계약 정본: docs/to-be/contracts.md §2.2
 */
@ApplicationModule(displayName = "neighborhood", allowedDependencies = { "shared", "member" })
package kr.weathertalk.neighborhood;

import org.springframework.modulith.ApplicationModule;
