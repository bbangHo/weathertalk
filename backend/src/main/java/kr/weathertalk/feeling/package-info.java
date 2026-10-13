/**
 * 체감: 체감 기록·피드백, 관측 스냅샷, 동네 체감, 민감도별 체감, 체감 매핑, 민감도 보정 제안. (docs/to-be/04-feeling.md)
 * <p>
 * 공개 API: 이 패키지의 타입. 구현은 {@code internal} 하위 패키지에 두며 다른 모듈에서 접근할 수 없다.
 * 계약 정본: docs/to-be/contracts.md §2.4
 */
@ApplicationModule(displayName = "feeling", allowedDependencies = { "shared", "member", "neighborhood", "weather" })
package kr.weathertalk.feeling;

import org.springframework.modulith.ApplicationModule;
