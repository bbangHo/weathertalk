package kr.weathertalk.shared.idempotency;

import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;

/**
 * 요청 식별자 보관 설정.
 *
 * @param ttl 처리 결과를 보관하는 기본 기간. 모듈별 값(예: {@code community.idempotency-window})이 있으면 그 값을 넘긴다.
 * @param processingLease 첫 요청이 처리 중임을 표시하는 기간. 처리 도중 인스턴스가 죽어도 이 시간이 지나면 재시도할 수 있다.
 */
@Validated
@ConfigurationProperties("shared.idempotency")
public record IdempotencyProperties(
		@NotNull @DurationMin(minutes = 1) Duration ttl,
		@NotNull @DurationMin(seconds = 1) Duration processingLease) {
}
