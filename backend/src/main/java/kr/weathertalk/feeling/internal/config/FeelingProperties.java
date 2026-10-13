package kr.weathertalk.feeling.internal.config;

import java.time.Duration;
import java.util.List;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * feeling 운영 파라미터 (README 운영 파라미터, 04-feeling.md §7). 외부화 패턴은 MemberProperties Javadoc 참조.
 * <p>
 * 아직 두지 않은 키: {@code feeling.reference-table}(BR-FEL-18 표 구조와 함께 M3), {@code feeling.mapping.seasons}·
 * {@code feeling.calibration.schedule}(매핑 재계산과 함께 M6, 보정 판정은 매핑 재계산 직후 이어서 실행).
 */
@Validated
@ConfigurationProperties("feeling")
public record FeelingProperties(
		@NotNull @DurationMin(minutes = 1) Duration neighborhoodWindow,
		@NotNull @Min(1) Integer minGroupRecords,
		@NotNull @DurationMin(seconds = 0) Duration recordInterval,
		@NotNull @Valid Neighborhood neighborhood,
		@NotNull @Valid Feedback feedback,
		@NotNull @Valid Snapshot snapshot,
		@NotNull @DurationMin(hours = 1) Duration pastLookupLimit,
		@NotNull @Valid Mapping mapping,
		@NotNull @Valid Comparison comparison,
		@NotNull @Valid Calibration calibration,
		@NotNull @Valid MyRecords myRecords,
		@NotNull @Valid MyPattern myPattern) {

	public record Neighborhood(
			@NotNull @Min(1) Integer minDisplayRecords,
			@NotNull @DecimalMin("0") Double feedbackWeight) {
	}

	public record Feedback(@NotNull @DurationMin(minutes = 1) Duration maxShownAge) {
	}

	public record Snapshot(
			@NotNull @DurationMin(minutes = 1) Duration maxAge,
			@NotNull @DurationMin(seconds = 0) Duration backfillWindow) {
	}

	/**
	 * @param schedule 재계산 cron(Asia/Seoul)
	 * @param tempMin 기온 구간 하한(℃)
	 * @param humidityBins 습도 구간 경계(%), 오름차순
	 * @param windBins 풍속 구간 경계(m/s), 오름차순
	 * @param maxDroppedRatio 단조성 위반으로 비운 축약 셀 비율 상한(0~1)
	 */
	public record Mapping(
			@NotBlank String schedule,
			@NotNull @DurationMin(days = 1) Duration lookback,
			@NotNull @Min(1) Integer minSamples,
			@NotNull @DecimalMin("0") Double feedbackWeight,
			@NotNull Integer tempMin,
			@NotNull Integer tempMax,
			@NotNull @Min(1) Integer tempBinWidth,
			@NotEmpty List<@NotNull Integer> humidityBins,
			@NotEmpty List<@NotNull Double> windBins,
			@NotNull @DecimalMin("0") @DecimalMax("1") Double maxDroppedRatio,
			@NotNull @Min(1) Integer retainedVersions) {

		@AssertTrue(message = "feeling.mapping.schedule은 올바른 cron 식이어야 합니다")
		public boolean isScheduleValid() {
			return schedule == null || CronExpression.isValidExpression(schedule);
		}

		@AssertTrue(message = "기온 구간은 temp-min < temp-max여야 합니다")
		public boolean isTempRangeValid() {
			return tempMin == null || tempMax == null || tempMin < tempMax;
		}

	}

	public record Comparison(@NotNull @Min(1) Integer largeDiff) {
	}

	/** @param minMargin 현재 그룹 대비 최소 오차 개선 폭(단계) */
	public record Calibration(
			@NotNull @DurationMin(days = 1) Duration lookback,
			@NotNull @Min(1) Integer minRecords,
			@NotNull @DecimalMin("0") Double feedbackWeight,
			@NotNull @DecimalMin("0") Double minMargin,
			@NotNull @DurationMin(seconds = 0) Duration cooldown,
			@NotNull @DurationMin(seconds = 0) Duration declineCooldown,
			@NotNull @DurationMin(days = 1) Duration offerTtl) {
	}

	public record MyRecords(@NotNull @DurationMin(days = 1) Duration period) {
	}

	/** @param tendencyThreshold "춥게/덥게" 판정 평균 단계 차 */
	public record MyPattern(
			@NotNull @Min(1) Integer minRecords,
			@NotNull @DecimalMin("0") Double tendencyThreshold) {
	}

}
