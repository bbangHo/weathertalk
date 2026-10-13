package kr.weathertalk.weather.internal.config;

import java.time.Duration;
import java.time.LocalTime;
import java.util.List;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * weather 운영 파라미터 (README 운영 파라미터, 03-weather.md §7). 외부화 패턴은 MemberProperties Javadoc 참조.
 */
@Validated
@ConfigurationProperties("weather")
public record WeatherProperties(
		@NotNull @Min(1) Integer midTermDays,
		@NotNull @Valid ShortTerm shortTerm,
		@NotNull @Valid Observation observation,
		@NotNull @Valid MidTerm midTerm,
		@NotNull @Valid Air air,
		@NotNull @Valid Uv uv,
		@NotNull @Valid External external,
		@NotNull @Valid Collect collect,
		@NotNull @Valid NextPrecip nextPrecip,
		@NotNull @Valid FeelsLike feelsLike,
		@NotNull @Valid RainPhrase rainPhrase,
		@NotNull @Valid FieldReport fieldReport,
		@NotNull @Valid Divergence divergence,
		@NotNull @Valid PrecipStart precipStart,
		@NotNull @Valid Retention retention,
		@NotNull @Valid DayNight dayNight) {

	public record ShortTerm(
			@NotNull @DurationMin(seconds = 0) Duration availabilityDelay,
			@NotNull @DurationMin(minutes = 1) Duration maxStaleness,
			@NotNull @Min(1) Integer defaultHours) {
	}

	public record Observation(
			@NotNull @DurationMin(seconds = 0) Duration availabilityDelay,
			@NotNull @DurationMin(minutes = 1) Duration maxStaleness) {
	}

	public record MidTerm(@NotNull @DurationMin(seconds = 0) Duration availabilityDelay) {
	}

	/**
	 * @param stationSyncInterval 측정소 목록(getMsrstnList) 정기 동기화 주기. 동네-측정소 대응은 이 목록으로 매번 계산하므로
	 *        스펙의 station-mapping-ttl(대응 재산정 주기)을 대신한다(03 구현 메모 §13).
	 */
	public record Air(
			@NotNull @DurationMin(seconds = 0) Duration availabilityDelay,
			@NotNull @DurationMin(minutes = 1) Duration maxStaleness,
			@NotNull @Min(1) Integer stationFallbackCount,
			@NotNull @DurationMin(hours = 1) Duration stationSyncInterval) {
	}

	public record Uv(@NotNull @DurationMin(seconds = 0) Duration availabilityDelay) {
	}

	public record External(
			@NotNull @DurationMin(millis = 100) Duration timeout,
			@NotNull @Min(1) Integer maxAttempts,
			@NotNull @DurationMin(seconds = 0) Duration retryBackoff) {
	}

	public record Collect(
			@NotNull @DurationMin(seconds = 0) Duration failureBackoff,
			@NotNull @DurationMin(millis = 100) Duration waitTimeout,
			@NotNull @DurationMin(seconds = 1) Duration lockLease,
			@NotNull @Min(1) Integer maxConcurrency,
			@NotNull @DurationMin(seconds = 1) Duration notReadyRetryInterval,
			@NotNull @DurationMin(seconds = 1) Duration notReadyMaxWait) {
	}

	public record NextPrecip(@NotNull @DurationMin(hours = 1) Duration horizon) {
	}

	/** 체감온도 공식 적용 조건(기상청 공식). 기온 ℃, 풍속 m/s. */
	public record FeelsLike(
			@NotNull Double winterMaxTemp,
			@NotNull @DecimalMin("0") Double winterMinWind,
			@NotEmpty List<@NotNull @Min(1) @Max(12) Integer> summerMonths,
			@NotNull Double summerMinTemp) {
	}

	/** 강수 문구 구간 하한. 비 mm, 눈 cm. */
	public record RainPhrase(
			@NotNull @DecimalMin("0") Double rainModerate,
			@NotNull @DecimalMin("0") Double rainHeavy,
			@NotNull @DecimalMin("0") Double rainExtreme,
			@NotNull @DecimalMin("0") Double snowModerate,
			@NotNull @DecimalMin("0") Double snowHeavy) {

		@AssertTrue(message = "강수 문구 구간 하한은 보통 < 강한 < 매우 강한 순이어야 합니다")
		public boolean isAscending() {
			if (rainModerate == null || rainHeavy == null || rainExtreme == null || snowModerate == null
					|| snowHeavy == null) {
				return true;
			}
			return rainModerate < rainHeavy && rainHeavy < rainExtreme && snowModerate < snowHeavy;
		}

	}

	public record FieldReport(
			@NotNull @Min(1) Integer rainThreshold,
			@NotNull @DurationMin(minutes = 1) Duration rainWindow,
			@NotNull @DurationMin(minutes = 1) Duration validWindow,
			@NotNull @DurationMin(seconds = 0) Duration minInterval,
			@NotNull @Min(1) Integer dailyLimit,
			@NotNull @DurationMin(seconds = 0) Duration cancelWindow,
			@NotNull @DurationMin(seconds = 0) Duration rainSuppress,
			@NotNull @DurationMin(days = 1) Duration retention) {
	}

	/** @param minShare 다수 종류로 인정할 최소 비율(0~1) */
	public record Divergence(
			@NotNull @Min(1) Integer minReports,
			@NotNull @DecimalMin("0.5") @DecimalMax("1.0") Double minShare) {
	}

	/** @param minPop 강수 시작으로 볼 최소 강수확률(%) */
	public record PrecipStart(
			@NotNull @DurationMin(minutes = 1) Duration leadWindow,
			@NotNull @Min(0) @Max(100) Integer minPop,
			@NotNull @DurationMin(seconds = 0) Duration sameEpisodeTolerance,
			@NotNull @DurationMin(minutes = 1) Duration episodeExpiry,
			@NotNull @DurationMin(minutes = 1) Duration dryGap) {
	}

	/** @param observation 실황·대기질 이력 보존. {@code 과거 관측 조회}가 48시간을 보장하므로 그보다 짧을 수 없다. */
	public record Retention(@NotNull @DurationMin(hours = 48) Duration observation) {
	}

	public record DayNight(
			@NotNull @DateTimeFormat(pattern = "HH:mm") LocalTime dayStart,
			@NotNull @DateTimeFormat(pattern = "HH:mm") LocalTime nightStart) {

		@AssertTrue(message = "낮 시작은 밤 시작보다 이르게 설정해야 합니다")
		public boolean isDayBeforeNight() {
			return dayStart == null || nightStart == null || dayStart.isBefore(nightStart);
		}

	}

}
