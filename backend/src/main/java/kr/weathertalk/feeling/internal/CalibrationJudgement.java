package kr.weathertalk.feeling.internal;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

import kr.weathertalk.feeling.FeelingRecordSource;
import kr.weathertalk.member.TemperatureSensitivity;

/**
 * 민감도 보정 제안 판정 계산 (BR-FEL-17 규칙 1~5). 빈도 제한·매핑 유무(규칙 6·7)는 호출자가 먼저 걸러낸다.
 * <ul>
 * <li>가중치: 직접 = 1, 피드백·직접+피드백 보정 = feeling.calibration.feedback-weight(보여준 값이 현재 그룹 기준이라 그쪽으로 끌리는 편향을 낮춘다).</li>
 * <li>가중 합이 min-records 미만이면 판정하지 않는다.</li>
 * <li>그룹별 가중 평균 오차 = Σ w × |기록 단계 − 그룹 예상 단계| / Σ w.</li>
 * <li>오차 최소 그룹이 현재 그룹이 아니고 (현재 오차 − 최소 오차) ≥ min-margin이면 그 그룹을 제안한다. 최소가 여럿이면 현재 그룹이 끼어 있으면 제안하지 않고,
 * 아니면 단계 순서(더위를 많이 탐 → 보통 → 추위를 많이 탐)로 앞선 그룹.</li>
 * </ul>
 */
final class CalibrationJudgement {

	/** @param suggested 제안할 그룹. 제안하지 않으면 null */
	record Outcome(int recordCount, double weightSum, Map<TemperatureSensitivity, Double> errors,
			TemperatureSensitivity suggested) {
	}

	private static final double EPSILON = 1e-9;

	private CalibrationJudgement() {
	}

	/**
	 * @param expected (그룹, 기록) → 그 그룹의 예상 온도 단계
	 * @return 가중 합이 minRecords 미만이면 빈 값(판정 안 함)
	 */
	static Optional<Outcome> judge(List<MappingSample> samples, TemperatureSensitivity current, double feedbackWeight,
			double minRecords, double minMargin, BiFunction<TemperatureSensitivity, MappingSample, Integer> expected) {
		double weightSum = 0;
		Map<TemperatureSensitivity, Double> sums = new EnumMap<>(TemperatureSensitivity.class);
		for (MappingSample sample : samples) {
			double w = sample.source() == FeelingRecordSource.DIRECT ? 1.0 : feedbackWeight;
			weightSum += w;
			for (TemperatureSensitivity group : TemperatureSensitivity.values()) {
				int diff = Math.abs(sample.temperatureLevel() - expected.apply(group, sample));
				sums.merge(group, w * diff, Double::sum);
			}
		}
		if (weightSum + EPSILON < minRecords || weightSum <= 0) {
			return Optional.empty();
		}
		Map<TemperatureSensitivity, Double> errors = new EnumMap<>(TemperatureSensitivity.class);
		for (TemperatureSensitivity group : TemperatureSensitivity.values()) {
			errors.put(group, sums.getOrDefault(group, 0.0) / weightSum);
		}
		double min = errors.values().stream().mapToDouble(Double::doubleValue).min().orElseThrow();
		TemperatureSensitivity suggested = null;
		if (errors.get(current) - min + EPSILON >= minMargin && errors.get(current) - min > EPSILON) {
			for (TemperatureSensitivity group : TemperatureSensitivity.values()) {
				if (Math.abs(errors.get(group) - min) < EPSILON) {
					suggested = group;
					break;
				}
			}
		}
		return Optional.of(new Outcome(samples.size(), weightSum, errors, suggested));
	}

}
