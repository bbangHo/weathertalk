package kr.weathertalk.feeling.internal;

import java.util.Map;
import java.util.TreeMap;

/**
 * 대표 단계 = 가중 중앙값 (BR-FEL-09). 체감 태그는 순서형 척도라 최빈값 대신 쓴다(legacy 03-#11: 현행의 "최빈값 정렬 후 마지막 제거" 폐지).
 * <ul>
 * <li>단계 순으로 가중치를 누적해 누적이 전체의 절반을 <b>처음 넘는</b> 단계.</li>
 * <li>어떤 단계에서 누적이 정확히 절반이면, 그 단계와 표본이 있는 다음 단계 중 범주의 중립 단계에 가까운 쪽.
 * 거리도 같으면 낮은 단계(문서에 정함이 없어 결정적으로 고른다).</li>
 * </ul>
 */
final class WeightedMedian {

	private static final double EPSILON = 1e-9;

	private WeightedMedian() {
	}

	/**
	 * @param weightByLevel 단계 → 가중치 합. 가중치가 0 이하인 단계는 표본이 없는 것으로 본다
	 * @return 가중치 합이 0이면 null
	 */
	static Integer of(Map<Integer, Double> weightByLevel, int neutralLevel) {
		TreeMap<Integer, Double> sorted = new TreeMap<>();
		weightByLevel.forEach((level, weight) -> {
			if (weight != null && weight > 0) {
				sorted.merge(level, weight, Double::sum);
			}
		});
		double total = sorted.values().stream().mapToDouble(Double::doubleValue).sum();
		if (total <= 0) {
			return null;
		}
		double half = total / 2;
		double cumulative = 0;
		for (Map.Entry<Integer, Double> entry : sorted.entrySet()) {
			cumulative += entry.getValue();
			if (Math.abs(cumulative - half) < EPSILON) {
				Integer next = sorted.higherKey(entry.getKey());
				if (next == null) {
					return entry.getKey();
				}
				int here = entry.getKey();
				return Math.abs(next - neutralLevel) < Math.abs(here - neutralLevel) ? next : here;
			}
			if (cumulative > half) {
				return entry.getKey();
			}
		}
		return sorted.lastKey();
	}

}
