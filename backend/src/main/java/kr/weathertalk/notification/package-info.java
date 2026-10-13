/**
 * 알림: 기기, 알림 구독, 외출 시각, 발송, 쿨다운. community는 이벤트 구독만 한다. (docs/to-be/06-notification.md)
 * <p>
 * 공개 API: 이 패키지의 타입. 구현은 {@code internal} 하위 패키지에 두며 다른 모듈에서 접근할 수 없다.
 * 계약 정본: docs/to-be/contracts.md §2.7
 */
@ApplicationModule(displayName = "notification", allowedDependencies = { "shared", "member", "neighborhood", "weather", "feeling", "outfit", "community" })
package kr.weathertalk.notification;

import org.springframework.modulith.ApplicationModule;
