/**
 * 날씨: 실황·단기·중기 예보, 대기질, 자외선, 체감온도, 현장 제보. (docs/to-be/03-weather.md)
 * <p>
 * 공개 API: 이 패키지의 타입. 구현은 {@code internal} 하위 패키지에 두며 다른 모듈에서 접근할 수 없다.
 * 계약 정본: docs/to-be/contracts.md §2.3
 */
@ApplicationModule(displayName = "weather", allowedDependencies = { "shared", "member", "neighborhood" })
package kr.weathertalk.weather;

import org.springframework.modulith.ApplicationModule;
