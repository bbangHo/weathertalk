package kr.weathertalk.notification.internal.config;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * notification 운영 파라미터 (README 운영 파라미터, 06-notification.md §7). 외부화 패턴은 MemberProperties Javadoc 참조.
 *
 * @param cooldown 강수 예보·현장 제보 강수 알림의 종류·동네별 쿨다운
 * @param quietHours 심야 발송 금지 시간(정기 날씨 알림에는 적용하지 않음 — ADR 0013). 자정을 넘길 수 있다.
 */
@Validated
@ConfigurationProperties("notification")
public record NotificationProperties(
		@NotNull @DurationMin(seconds = 0) Duration cooldown,
		@NotNull @Valid QuietHours quietHours,
		@NotNull ZoneId timezone,
		@NotNull @Valid Daily daily,
		@NotNull @Valid Event event,
		@NotNull @Valid Comment comment,
		@NotNull @Valid Departure departure,
		@NotNull @Valid Subscription subscription,
		@NotNull @Valid Send send,
		@NotNull @Valid Device device,
		@NotNull @Valid DeliveryLog deliveryLog,
		@NotNull @Valid Message message,
		@NotNull @Valid Test test) {

	// 모든 시각 계산은 Asia/Seoul (contracts.md §4). 키는 스펙대로 두되 다른 값은 기동 단계에서 막는다.
	@AssertTrue(message = "notification.timezone은 Asia/Seoul이어야 합니다")
	public boolean isTimezoneSeoul() {
		return timezone == null || timezone.getId().equals("Asia/Seoul");
	}

	public record QuietHours(
			@NotNull @DateTimeFormat(pattern = "HH:mm") LocalTime start,
			@NotNull @DateTimeFormat(pattern = "HH:mm") LocalTime end) {
	}

	/**
	 * @param timeUnit 정기 알림 시각 지정 단위 = 발송 회차 간격
	 * @param defaultTime 첫 구독의 정기 알림 기본 시각
	 */
	public record Daily(
			@NotNull @DurationMin(minutes = 1) Duration timeUnit,
			@NotNull @Min(1) Integer maxTimes,
			@NotNull @DateTimeFormat(pattern = "HH:mm") LocalTime defaultTime,
			@NotNull @DurationMin(hours = 1) Duration forecastSpan,
			@NotNull @DurationMin(seconds = 0) Duration prepareAhead,
			@NotNull @DurationMin(minutes = 1) Duration maxDelay) {
	}

	public record Event(@NotNull @DurationMin(minutes = 1) Duration maxDelay) {
	}

	public record Comment(@NotNull @DurationMin(seconds = 0) Duration cooldown) {
	}

	public record Departure(@NotNull @Min(1) Integer maxTimes) {
	}

	public record Subscription(@NotNull @Min(1) Integer maxPerDevice) {
	}

	/**
	 * @param batchSize 다건 발송 1회당 건수. FCM 다건 발송 한도가 500건이다.
	 * @param invalidTokenCodes 무효 토큰으로 분류할 제공자 응답 코드
	 */
	public record Send(
			@NotNull @Min(1) @Max(500) Integer batchSize,
			@NotNull @Min(1) Integer concurrency,
			@NotNull @Min(0) Integer maxRetries,
			@NotNull @DurationMin(seconds = 0) Duration retryBackoff,
			@NotEmpty List<@NotBlank String> invalidTokenCodes) {
	}

	public record Device(
			@NotNull @DurationMin(days = 1) Duration staleAfter,
			@NotNull @DurationMin(days = 1) Duration purgeAfter,
			@NotNull @Min(1) Integer pushTokenMaxLength) {
	}

	public record DeliveryLog(@NotNull @DurationMin(days = 1) Duration retention) {
	}

	public record Message(@NotNull @Min(1) Integer titleMaxLength, @NotNull @Min(1) Integer bodyMaxLength) {
	}

	/**
	 * @param enabled 테스트 발송 기능 등록 여부. 운영(prod) 프로파일 yml에서 false로 고정한다(contracts.md §4 개발용 기능).
	 */
	public record Test(@NotNull Boolean enabled, @NotNull @Valid RateLimit rateLimit) {
	}

	/** {@code period} 동안 기기당 {@code maxCount}회. */
	public record RateLimit(@NotNull @Min(1) Integer maxCount, @NotNull @DurationMin(minutes = 1) Duration period) {
	}

}
