package kr.weathertalk.neighborhood.internal.config;

import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * neighborhood 운영 파라미터 (02-neighborhood.md §7). 외부화 패턴은 MemberProperties Javadoc 참조.
 * 위치 판정 관련 값(최대 거리·쓰기 정확도)은 판정을 하는 앱 설정(app/src/config/appParams)에 있다(ADR 0009).
 */
@Validated
@ConfigurationProperties("neighborhood")
public record NeighborhoodProperties(
		@NotNull @Min(1) Integer maxInterest,
		@NotNull @DurationMin(days = 1) Duration activeMemberWindow,
		@NotNull @Min(1) Integer searchMaxResults,
		@NotNull @Min(1) Integer searchMinLength) {
}
