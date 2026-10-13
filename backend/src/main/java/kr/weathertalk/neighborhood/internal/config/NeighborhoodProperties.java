package kr.weathertalk.neighborhood.internal.config;

import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * neighborhood 운영 파라미터 (02-neighborhood.md §7). 외부화 패턴은 MemberProperties Javadoc 참조.
 *
 * @param maxAccuracyForWrite 현재 동네 확정에 허용하는 최대 위치 오차(미터)
 * @param maxNearestDistance 최근접 대표점 판정에서 이 거리(미터)보다 멀면 판정 불가(바다 위·국외, ADR 0016)
 */
@Validated
@ConfigurationProperties("neighborhood")
public record NeighborhoodProperties(
		@NotNull @Min(1) Integer maxInterest,
		@NotNull @DurationMin(seconds = 0) Duration currentRefreshMinInterval,
		@NotNull @Min(1) Integer maxAccuracyForWrite,
		@NotNull @DurationMin(days = 1) Duration activeMemberWindow,
		@NotNull @Min(1) Integer searchMaxResults,
		@NotNull @Min(1) Integer searchMinLength,
		@NotNull @Min(1) Integer maxNearestDistance) {
}
