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
import jakarta.validation.constraints.Size;

/**
 * feeling 운영 파라미터 (README 운영 파라미터, 04-feeling.md §7). 외부화 패턴은 MemberProperties Javadoc 참조.
 * <p>
 * 아직 두지 않은 키: {@code feeling.mapping.seasons}·{@code feeling.calibration.schedule}(매핑 재계산과 함께 M6,
 * 보정 판정은 매핑 재계산 직후 이어서 실행).
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
		@NotNull @Valid ReferenceTable referenceTable,
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
	 * 고정 기준표 (BR-FEL-18). 그룹마다 온도 태그 7·6·5·4·3·2단계의 기온 하한(℃, 이상)을 이 순서로 6개 둔다. 어느 하한에도
	 * 못 미치면 1단계. 하한은 단계가 내려갈수록 엄격히 낮아야 한다(단조, legacy 03-#13).
	 */
	public record ReferenceTable(
			@NotNull @Size(min = 6, max = 6) List<@NotNull Double> heatSensitive,
			@NotNull @Size(min = 6, max = 6) List<@NotNull Double> normal,
			@NotNull @Size(min = 6, max = 6) List<@NotNull Double> coldSensitive) {

		@AssertTrue(message = "feeling.reference-table 하한은 높은 단계부터 엄격히 내려가야 합니다")
		public boolean isMonotonic() {
			return descending(heatSensitive) && descending(normal) && descending(coldSensitive);
		}

		private static boolean descending(List<Double> bounds) {
			if (bounds == null) {
				return true;
			}
			for (int i = 1; i < bounds.size(); i++) {
				if (bounds.get(i) == null || bounds.get(i - 1) == null || bounds.get(i) >= bounds.get(i - 1)) {
					return false;
				}
			}
			return true;
		}

	}

	/**
	 * @param enabled 민감도별 체감 2단계(체감 매핑) 사용 여부. 매핑 재계산은 M6에서 구현하며 그 전까지 꺼 둔다(기능 플래그)
	 * @param schedule 재계산 cron(Asia/Seoul)
	 * @param tempMin 기온 구간 하한(℃)
	 * @param humidityBins 습도 구간 경계(%), 오름차순
	 * @param windBins 풍속 구간 경계(m/s), 오름차순
	 * @param maxDroppedRatio 단조성 위반으로 비운 축약 셀 비율 상한(0~1)
	 */
	public record Mapping(
			@NotNull Boolean enabled,
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
