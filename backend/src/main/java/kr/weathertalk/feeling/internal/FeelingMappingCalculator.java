package kr.weathertalk.feeling.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import kr.weathertalk.feeling.FeelingRecordSource;
import kr.weathertalk.feeling.HumidityTag;
import kr.weathertalk.feeling.MappingLayer;
import kr.weathertalk.feeling.TemperatureTag;
import kr.weathertalk.feeling.WindTag;
import kr.weathertalk.member.TemperatureSensitivity;

/**
 * 체감 매핑 셀 계산과 검증 (FR-FEL-06 규칙 1~4, BR-FEL-13~15). 저장소와 무관한 순수 계산이라 표본을 하나씩 넣고 {@link #finish}로 끝낸다.
 * <ul>
 * <li>가중치: 직접 = 1, 피드백 = feedback-weight. 직접+피드백 보정은 온도만 피드백 가중치이고 습도·바람은 1(그 태그는 직접 기록 것이므로).</li>
 * <li>세 층(세부·계절 없음·축약)마다 범주별 가중 중앙값(BR-FEL-09). 온도 가중 합이 min-samples 미만인 셀은 비운다.
 * 습도·바람도 그 범주의 가중 합이 min-samples 이상일 때만 담는다.</li>
 * <li>검증: 그룹마다 축약 셀의 온도 단계가 기온 구간을 따라 내려가지 않아야 한다. 위반 셀은 가장 적게 비우는 방법(최장 비감소
 * 부분열 밖의 셀)으로 비우고, 비운 비율이 max-dropped-ratio를 넘거나 채운 셀(세부+축약)이 하나도 없으면 거부한다.</li>
 * </ul>
 */
final class FeelingMappingCalculator {

	/** 검증 결과 (BR-FEL-15). 매핑 버전에 그대로 저장한다. */
	record Validation(boolean passed, String rejectReason, int sampleCount, int detailCells, int noSeasonCells,
			int coarseCells, int droppedCoarseCells, double droppedRatio, List<String> warnings) {
	}

	record Result(List<MappingCell> cells, Validation validation) {
	}

	private static final class Accumulator {

		final Map<Integer, Double> temperature = new HashMap<>();
		final Map<Integer, Double> humidity = new HashMap<>();
		final Map<Integer, Double> wind = new HashMap<>();
		double temperatureWeight;
		double humidityWeight;
		double windWeight;
		int count;

	}

	private final MappingSettings settings;
	private final double maxDroppedRatio;
	private final Map<MappingCell.Key, Accumulator> accumulators = new LinkedHashMap<>();
	private int sampleCount;

	FeelingMappingCalculator(MappingSettings settings, double maxDroppedRatio) {
		this.settings = settings;
		this.maxDroppedRatio = maxDroppedRatio;
	}

	void add(MappingSample sample) {
		sampleCount++;
		double temperatureWeight = sample.source() == FeelingRecordSource.DIRECT ? 1.0 : settings.feedbackWeight();
		Season season = settings.seasonOf(sample.recordedAt());
		int t = settings.temperatureBinOf(sample.temperature());
		int h = settings.humidityBinOf(sample.humidity());
		int w = settings.windBinOf(sample.windSpeed());
		for (MappingCell.Key key : List.of(MappingCell.Key.detail(sample.sensitivity(), season, t, h, w),
				MappingCell.Key.noSeason(sample.sensitivity(), t, h, w),
				MappingCell.Key.coarse(sample.sensitivity(), t))) {
			Accumulator acc = accumulators.computeIfAbsent(key, k -> new Accumulator());
			acc.count++;
			acc.temperature.merge(sample.temperatureLevel(), temperatureWeight, Double::sum);
			acc.temperatureWeight += temperatureWeight;
			if (sample.humidityLevel() != null) {
				acc.humidity.merge(sample.humidityLevel(), 1.0, Double::sum);
				acc.humidityWeight += 1.0;
			}
			if (sample.windLevel() != null) {
				acc.wind.merge(sample.windLevel(), 1.0, Double::sum);
				acc.windWeight += 1.0;
			}
		}
	}

	Result finish() {
		int min = settings.minSamples();
		List<MappingCell> cells = new ArrayList<>();
		accumulators.forEach((key, acc) -> {
			if (acc.temperatureWeight < min) {
				return;
			}
			Integer humidity = acc.humidityWeight >= min ? WeightedMedian.of(acc.humidity, HumidityTag.NEUTRAL.level())
					: null;
			Integer wind = acc.windWeight >= min ? WeightedMedian.of(acc.wind, WindTag.NEUTRAL.level()) : null;
			cells.add(new MappingCell(key,
					TemperatureTag.of(WeightedMedian.of(acc.temperature, TemperatureTag.NEUTRAL.level())),
					humidity == null ? null : HumidityTag.of(humidity), wind == null ? null : WindTag.of(wind), acc.count,
					acc.temperatureWeight));
		});

		List<String> warnings = new ArrayList<>();
		List<MappingCell> dropped = monotonicViolations(cells, warnings);
		long coarseBefore = cells.stream().filter(c -> c.key().layer() == MappingLayer.COARSE).count();
		cells.removeAll(dropped);
		int detail = count(cells, MappingLayer.DETAIL);
		int noSeason = count(cells, MappingLayer.NO_SEASON);
		int coarse = count(cells, MappingLayer.COARSE);
		double ratio = coarseBefore == 0 ? 0 : (double) dropped.size() / coarseBefore;
		String reject = null;
		if (detail + coarse == 0) {
			reject = "NO_FILLED_CELL";
		}
		else if (ratio > maxDroppedRatio) {
			reject = "TOO_MANY_MONOTONICITY_VIOLATIONS";
		}
		cells.sort(Comparator.comparing((MappingCell c) -> c.key().layer())
			.thenComparing(c -> c.key().sensitivity())
			.thenComparing(c -> c.key().season(), Comparator.nullsFirst(Comparator.naturalOrder()))
			.thenComparingInt(c -> c.key().temperatureBin())
			.thenComparing(c -> c.key().humidityBin(), Comparator.nullsFirst(Comparator.naturalOrder()))
			.thenComparing(c -> c.key().windBin(), Comparator.nullsFirst(Comparator.naturalOrder())));
		return new Result(cells, new Validation(reject == null, reject, sampleCount, detail, noSeason, coarse,
				dropped.size(), ratio, List.copyOf(warnings)));
	}

	/** 그룹마다 축약 셀을 기온 구간 순으로 놓고, 최장 비감소 부분열에 들지 않는 셀(가장 적게 비우는 선택)을 돌려준다. */
	private static List<MappingCell> monotonicViolations(List<MappingCell> cells, List<String> warnings) {
		List<MappingCell> violations = new ArrayList<>();
		for (TemperatureSensitivity sensitivity : TemperatureSensitivity.values()) {
			List<MappingCell> row = cells.stream()
				.filter(c -> c.key().layer() == MappingLayer.COARSE && c.key().sensitivity() == sensitivity)
				.sorted(Comparator.comparingInt(c -> c.key().temperatureBin()))
				.toList();
			int n = row.size();
			int[] length = new int[n];
			int[] previous = new int[n];
			int best = -1;
			for (int i = 0; i < n; i++) {
				length[i] = 1;
				previous[i] = -1;
				for (int j = 0; j < i; j++) {
					if (row.get(j).temperature().level() <= row.get(i).temperature().level()
							&& length[j] + 1 > length[i]) {
						length[i] = length[j] + 1;
						previous[i] = j;
					}
				}
				if (best < 0 || length[i] > length[best]) {
					best = i;
				}
			}
			boolean[] keep = new boolean[n];
			for (int i = best; i >= 0; i = previous[i]) {
				keep[i] = true;
			}
			for (int i = 0; i < n; i++) {
				if (!keep[i]) {
					MappingCell cell = row.get(i);
					violations.add(cell);
					warnings.add("단조성 위반으로 비운 축약 셀: " + sensitivity + " 기온 구간 " + cell.key().temperatureBin()
							+ " 단계 " + cell.temperature().level());
				}
			}
		}
		return violations;
	}

	private static int count(List<MappingCell> cells, MappingLayer layer) {
		return (int) cells.stream().filter(c -> c.key().layer() == layer).count();
	}

}
