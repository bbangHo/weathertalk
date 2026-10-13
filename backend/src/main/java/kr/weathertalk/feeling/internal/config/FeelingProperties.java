package kr.weathertalk.feeling.internal.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
 * 스펙(§7)에 없는 키: {@code feeling.snapshot.backfill-interval}(미확보 스냅샷 백필 주기 — 백필 창이 24시간이라 하루 한 번으로는
 * 빈틈이 생긴다).
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

	/** @param backfillInterval 미확보 스냅샷 백필(BR-FEL-05) 실행 간격 */
	public record Snapshot(
			@NotNull @DurationMin(minutes = 1) Duration maxAge,
			@NotNull @DurationMin(seconds = 0) Duration backfillWindow,
			@NotNull @DurationMin(minutes = 1) Duration backfillInterval) {
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
	 * @param enabled 체감 매핑 사용 여부(기능 플래그). 끄면 민감도별 체감 2단계·내 체감 패턴·보정 제안 판정이 적용 중인 매핑을 쓰지
	 *        않는다. 재계산 배치는 꺼져 있어도 돌아 버전(셀 충족률·적중률)을 쌓는다. 데이터가 쌓이면 켠다(implementation-plan M6)
	 * @param schedule 재계산 cron(Asia/Seoul)
	 * @param seasons 계절 구분(월 → 계절, BR-FEL-14)
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
			@NotNull @Min(1) Integer retainedVersions,
			@NotNull @Valid Seasons seasons) {

		@AssertTrue(message = "feeling.mapping.humidity-bins·wind-bins는 엄격히 오름차순이어야 합니다")
		public boolean isBinsAscending() {
			return ascending(humidityBins) && ascending(windBins);
		}

		private static boolean ascending(List<? extends Number> bounds) {
			if (bounds == null) {
				return true;
			}
			for (int i = 1; i < bounds.size(); i++) {
				if (bounds.get(i) == null || bounds.get(i - 1) == null
						|| bounds.get(i).doubleValue() <= bounds.get(i - 1).doubleValue()) {
					return false;
				}
			}
			return true;
		}

		@AssertTrue(message = "feeling.mapping.schedule은 올바른 cron 식이어야 합니다")
		public boolean isScheduleValid() {
			return schedule == null || CronExpression.isValidExpression(schedule);
		}

		@AssertTrue(message = "기온 구간은 temp-min < temp-max여야 합니다")
		public boolean isTempRangeValid() {
			return tempMin == null || tempMax == null || tempMin < tempMax;
		}

	}

	/** 계절 구분 (BR-FEL-14). 1~12월이 각각 정확히 한 계절에 들어야 한다. */
	public record Seasons(
			@NotEmpty List<@NotNull Integer> spring,
			@NotEmpty List<@NotNull Integer> summer,
			@NotEmpty List<@NotNull Integer> autumn,
			@NotEmpty List<@NotNull Integer> winter) {

		@AssertTrue(message = "feeling.mapping.seasons는 1~12월을 각각 정확히 한 번씩 담아야 합니다")
		public boolean isEveryMonthOnce() {
			if (spring == null || summer == null || autumn == null || winter == null) {
				return true;
			}
			List<Integer> all = new ArrayList<>();
			all.addAll(spring);
			all.addAll(summer);
			all.addAll(autumn);
			all.addAll(winter);
			return all.size() == 12
					&& new HashSet<>(all).equals(IntStream.rangeClosed(1, 12).boxed().collect(Collectors.toSet()));
		}

	}

	public record Comparison(@NotNull @Min(1) Integer largeDiff) {
	}

	/**
	 * @param schedule 판정 cron(Asia/Seoul). 매핑 재계산 직후로 둔다(FR-FEL-07)
	 * @param minMargin 현재 그룹 대비 최소 오차 개선 폭(단계)
	 */
	public record Calibration(
			@NotBlank String schedule,
			@NotNull @DurationMin(days = 1) Duration lookback,
			@NotNull @Min(1) Integer minRecords,
			@NotNull @DecimalMin("0") Double feedbackWeight,
			@NotNull @DecimalMin("0") Double minMargin,
			@NotNull @DurationMin(seconds = 0) Duration cooldown,
			@NotNull @DurationMin(seconds = 0) Duration declineCooldown,
			@NotNull @DurationMin(days = 1) Duration offerTtl) {

		@AssertTrue(message = "feeling.calibration.schedule은 올바른 cron 식이어야 합니다")
		public boolean isScheduleValid() {
			return schedule == null || CronExpression.isValidExpression(schedule);
		}

	}

	public record MyRecords(@NotNull @DurationMin(days = 1) Duration period) {
	}

	/** @param tendencyThreshold "춥게/덥게" 판정 평균 단계 차 */
	public record MyPattern(
			@NotNull @Min(1) Integer minRecords,
			@NotNull @DecimalMin("0") Double tendencyThreshold) {
	}

}
