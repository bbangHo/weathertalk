package kr.weathertalk.feeling.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import kr.weathertalk.feeling.FeelingRecordSource;
import kr.weathertalk.feeling.HumidityTag;
import kr.weathertalk.feeling.MappingLayer;
import kr.weathertalk.feeling.TemperatureTag;
import kr.weathertalk.member.TemperatureSensitivity;
import kr.weathertalk.weather.WeatherStubs;

/**
 * 체감 매핑 규칙(순수 계산): 기상 조건 구간·계절(BR-FEL-14), 셀 계산·가중치·최소 표본(FR-FEL-06), 단조성 검증(BR-FEL-15),
 * 셀 대체 순서(BR-FEL-13 2단계), 보정 제안 판정 경계(BR-FEL-17).
 */
class FeelingMappingRulesTests {

	static final MappingSettings SETTINGS = new MappingSettings(-15, 35, 2, List.of(40, 70), List.of(2.0, 5.0),
			Map.of(Season.SPRING, List.of(3, 4, 5), Season.SUMMER, List.of(6, 7, 8), Season.AUTUMN, List.of(9, 10, 11),
					Season.WINTER, List.of(12, 1, 2)),
			30, 0.5);

	static final Instant AUTUMN = WeatherStubs.kst("2026-10-13T09:00");

	static final Instant SPRING = WeatherStubs.kst("2026-04-13T09:00");

	/** 기온 구간: 양 끝 열린 구간, 경계는 위 구간("이상"). 습도·풍속도 경계값은 위 구간. */
	@ParameterizedTest
	@CsvSource({ "-30, 0", "-15.01, 0", "-15, 1", "-13.01, 1", "-13, 2", "19.99, 18", "20, 18", "34.99, 25",
			"35, 26", "50, 26" })
	void temperatureBins(double temperature, int bin) {
		assertThat(SETTINGS.temperatureBinOf(temperature)).isEqualTo(bin);
		assertThat(SETTINGS.temperatureBinCount()).isEqualTo(27);
	}

	@Test
	void humidityWindBinsAndSeason() {
		assertThat(SETTINGS.humidityBinOf(39.9)).isZero();
		assertThat(SETTINGS.humidityBinOf(40)).isEqualTo(1);
		assertThat(SETTINGS.humidityBinOf(70)).isEqualTo(2);
		assertThat(SETTINGS.windBinOf(1.9)).isZero();
		assertThat(SETTINGS.windBinOf(2.0)).isEqualTo(1);
		assertThat(SETTINGS.windBinOf(5.0)).isEqualTo(2);
		// 계절은 한국 표준시 날짜로: 11월 30일 23:30(KST)은 가을, 12월 1일 00:30(KST)은 겨울
		assertThat(SETTINGS.seasonOf(WeatherStubs.kst("2026-11-30T23:30"))).isEqualTo(Season.AUTUMN);
		assertThat(SETTINGS.seasonOf(WeatherStubs.kst("2026-12-01T00:30"))).isEqualTo(Season.WINTER);
		assertThat(SETTINGS.seasonOf(WeatherStubs.kst("2026-02-28T12:00"))).isEqualTo(Season.WINTER);
	}

	private static MappingSample sample(TemperatureSensitivity sensitivity, Instant at, int level, Integer humidity,
			FeelingRecordSource source, double temperature) {
		return new MappingSample(null, sensitivity, at, level, humidity, null, source, temperature, 60, 2.0);
	}

	private static List<MappingSample> repeat(int count, MappingSample sample) {
		List<MappingSample> list = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			list.add(sample);
		}
		return list;
	}

	private static FeelingMappingCalculator.Result calculate(List<MappingSample> samples) {
		FeelingMappingCalculator calculator = new FeelingMappingCalculator(SETTINGS, 0.2);
		samples.forEach(calculator::add);
		return calculator.finish();
	}

	private static Map<MappingLayer, List<MappingCell>> byLayer(FeelingMappingCalculator.Result result) {
		return result.cells().stream().collect(Collectors.groupingBy(c -> c.key().layer()));
	}

	/** 한 표본이 세 층(세부·계절 없음·축약)에 모두 들어가고, 최소 표본(30) 미만 셀은 비운다(경계값). */
	@Test
	void cellsAreBuiltOnThreeLayersFromMinSamples() {
		List<MappingSample> samples = new ArrayList<>(
				repeat(30, sample(TemperatureSensitivity.NORMAL, AUTUMN, 5, 4, FeelingRecordSource.DIRECT, 20.0)));
		samples.addAll(repeat(29, sample(TemperatureSensitivity.NORMAL, AUTUMN, 2, null, FeelingRecordSource.DIRECT, 5.0)));

		FeelingMappingCalculator.Result result = calculate(samples);

		assertThat(result.validation().passed()).isTrue();
		Map<MappingLayer, List<MappingCell>> layers = byLayer(result);
		assertThat(layers.get(MappingLayer.DETAIL)).singleElement().satisfies(c -> {
			assertThat(c.key()).isEqualTo(MappingCell.Key.detail(TemperatureSensitivity.NORMAL, Season.AUTUMN, 18, 1, 1));
			assertThat(c.temperature()).isEqualTo(TemperatureTag.SLIGHTLY_HOT);
			assertThat(c.humidity()).isEqualTo(HumidityTag.HUMID);
			assertThat(c.wind()).isNull(); // 바람 표본 없음
			assertThat(c.sampleCount()).isEqualTo(30);
		});
		assertThat(layers.get(MappingLayer.NO_SEASON)).singleElement()
			.satisfies(c -> assertThat(c.key()).isEqualTo(MappingCell.Key.noSeason(TemperatureSensitivity.NORMAL, 18, 1, 1)));
		assertThat(layers.get(MappingLayer.COARSE)).singleElement()
			.satisfies(c -> assertThat(c.key()).isEqualTo(MappingCell.Key.coarse(TemperatureSensitivity.NORMAL, 18)));
		assertThat(result.validation().sampleCount()).isEqualTo(59);
	}

	/**
	 * 가중치(FR-FEL-06 규칙 1): 피드백 = 0.5라 피드백 59건(가중 29.5)은 최소 표본 미달, 60건이면 채워진다. 직접+피드백 보정은 온도만 0.5,
	 * 습도는 1로 센다.
	 */
	@Test
	void feedbackWeightAppliesToTemperatureOnly() {
		assertThat(calculate(repeat(59,
				sample(TemperatureSensitivity.NORMAL, AUTUMN, 4, null, FeelingRecordSource.FEEDBACK, 20.0))).cells())
			.isEmpty();
		assertThat(calculate(repeat(60,
				sample(TemperatureSensitivity.NORMAL, AUTUMN, 4, null, FeelingRecordSource.FEEDBACK, 20.0))).cells())
			.hasSize(3);

		// 직접+피드백 보정 40건: 온도 가중 20(미달) → 셀 없음
		assertThat(calculate(repeat(40, sample(TemperatureSensitivity.NORMAL, AUTUMN, 4, 3,
				FeelingRecordSource.DIRECT_WITH_FEEDBACK, 20.0))).cells()).isEmpty();
		// 직접 20건 + 직접+피드백 보정 20건: 온도 가중 30(충족), 습도 가중 40 — 습도는 보정 기록도 1로 세어 대표 단계가 보정 기록 쪽(5)
		List<MappingSample> mixed = new ArrayList<>(
				repeat(20, sample(TemperatureSensitivity.NORMAL, AUTUMN, 4, 1, FeelingRecordSource.DIRECT, 20.0)));
		mixed.addAll(repeat(21,
				sample(TemperatureSensitivity.NORMAL, AUTUMN, 6, 5, FeelingRecordSource.DIRECT_WITH_FEEDBACK, 20.0)));
		MappingCell coarse = byLayer(calculate(mixed)).get(MappingLayer.COARSE).getFirst();
		assertThat(coarse.weightedCount()).isEqualTo(30.5);
		// 온도: 4단계 가중 20, 6단계 가중 10.5 → 4. 습도: 1단계 20, 5단계 21 → 5
		assertThat(coarse.temperature()).isEqualTo(TemperatureTag.JUST_RIGHT);
		MappingCell detail = byLayer(calculate(mixed)).get(MappingLayer.DETAIL).getFirst();
		assertThat(detail.humidity()).isEqualTo(HumidityTag.VERY_HUMID);
	}

	/** BR-FEL-15: 축약 셀이 기온을 따라 내려가면 가장 적게 비우고(경고), 비운 비율이 상한 이하면 통과한다. */
	@Test
	void monotonicityViolationDropsFewestCoarseCells() {
		List<MappingSample> samples = new ArrayList<>();
		int[] levels = { 1, 2, 3, 2, 4, 5, 6 }; // 4번째(2단계)만 역행
		double[] temperatures = { -10, -2, 6, 10, 18, 24, 30 };
		for (int i = 0; i < levels.length; i++) {
			samples.addAll(repeat(30, sample(TemperatureSensitivity.COLD_SENSITIVE, AUTUMN, levels[i], null,
					FeelingRecordSource.DIRECT, temperatures[i])));
		}
		FeelingMappingCalculator.Result result = calculate(samples);
		assertThat(result.validation().passed()).isTrue();
		assertThat(result.validation().droppedCoarseCells()).isEqualTo(1);
		assertThat(result.validation().droppedRatio()).isCloseTo(1.0 / 7, org.assertj.core.data.Offset.offset(1e-9));
		assertThat(result.validation().warnings()).singleElement().asString().contains("COLD_SENSITIVE");
		assertThat(byLayer(result).get(MappingLayer.COARSE)).hasSize(6)
			.noneMatch(c -> c.key().temperatureBin() == SETTINGS.temperatureBinOf(10));
		// 세부·계절 없는 셀은 비우지 않는다(검증 대상은 축약 셀)
		assertThat(byLayer(result).get(MappingLayer.DETAIL)).hasSize(7);
	}

	/** BR-FEL-15: 비운 비율이 max-dropped-ratio(0.2)를 넘으면 버전 전체 거부. 채운 셀이 없어도 거부. */
	@Test
	void versionIsRejectedWhenTooManyViolationsOrNoCells() {
		List<MappingSample> samples = new ArrayList<>();
		int[] levels = { 6, 3, 2 };
		double[] temperatures = { 10, 20, 26 };
		for (int i = 0; i < levels.length; i++) {
			samples.addAll(repeat(30, sample(TemperatureSensitivity.NORMAL, AUTUMN, levels[i], null,
					FeelingRecordSource.DIRECT, temperatures[i])));
		}
		FeelingMappingCalculator.Validation rejected = calculate(samples).validation();
		assertThat(rejected.passed()).isFalse();
		assertThat(rejected.rejectReason()).isEqualTo("TOO_MANY_MONOTONICITY_VIOLATIONS");
		assertThat(rejected.droppedCoarseCells()).isEqualTo(2);

		FeelingMappingCalculator.Validation empty = calculate(
				repeat(10, sample(TemperatureSensitivity.NORMAL, AUTUMN, 4, null, FeelingRecordSource.DIRECT, 20.0)))
			.validation();
		assertThat(empty.passed()).isFalse();
		assertThat(empty.rejectReason()).isEqualTo("NO_FILLED_CELL");
	}

	/** BR-FEL-13 2단계 대체 순서: 세부(계절 일치) → 계절 없음 → 축약. 습도·풍속을 모르면 축약 셀로 바로 간다. 기온이 없으면 없음. */
	@Test
	void lookupFallsBackDetailNoSeasonCoarse() {
		TemperatureSensitivity g = TemperatureSensitivity.NORMAL;
		MappingCell detail = cell(MappingCell.Key.detail(g, Season.AUTUMN, 18, 1, 1), TemperatureTag.SLIGHTLY_HOT);
		MappingCell noSeason = cell(MappingCell.Key.noSeason(g, 18, 1, 1), TemperatureTag.JUST_RIGHT);
		MappingCell coarse = cell(MappingCell.Key.coarse(g, 18), TemperatureTag.SLIGHTLY_COLD);
		LoadedMapping mapping = new LoadedMapping(7, SETTINGS,
				List.of(detail, noSeason, coarse).stream().collect(Collectors.toMap(MappingCell::key, Function.identity())));

		assertThat(mapping.find(g, AUTUMN, 20.0, 60.0, 2.0)).contains(detail);
		assertThat(mapping.find(g, SPRING, 20.0, 60.0, 2.0)).contains(noSeason); // 봄 세부 셀 없음
		assertThat(mapping.find(g, AUTUMN, 20.0, 80.0, 2.0)).contains(coarse); // 습함 구간 셀 없음
		assertThat(mapping.find(g, AUTUMN, 20.0, null, 2.0)).contains(coarse);
		assertThat(mapping.find(g, AUTUMN, 30.0, 60.0, 2.0)).isEmpty(); // 그 기온 구간 셀 없음 → 기준표로
		assertThat(mapping.find(g, AUTUMN, null, 60.0, 2.0)).isEmpty();
		assertThat(mapping.find(TemperatureSensitivity.HEAT_SENSITIVE, AUTUMN, 20.0, 60.0, 2.0)).isEmpty();

		// 예상 단계: 매핑 셀이 없으면 고정 기준표(보통 30℃ = 6 더움)
		ReferenceTable table = new ReferenceTable(FeelingRulesTests.TABLE);
		assertThat(FeelingMappingLookup.expected(Optional.of(mapping), table, g, AUTUMN, 20.0, 60.0, 2.0))
			.isEqualTo(TemperatureTag.SLIGHTLY_HOT);
		assertThat(FeelingMappingLookup.expected(Optional.of(mapping), table, g, AUTUMN, 30.0, 60.0, 2.0))
			.isEqualTo(TemperatureTag.HOT);
		assertThat(FeelingMappingLookup.expected(Optional.empty(), table, g, AUTUMN, 20.0, 60.0, 2.0))
			.isEqualTo(TemperatureTag.JUST_RIGHT);
	}

	private static MappingCell cell(MappingCell.Key key, TemperatureTag tag) {
		return new MappingCell(key, tag, null, null, 30, 30);
	}

	private static Optional<CalibrationJudgement.Outcome> judge(List<MappingSample> samples,
			Map<TemperatureSensitivity, Integer> expected) {
		return CalibrationJudgement.judge(samples, TemperatureSensitivity.NORMAL, 0.5, 20, 0.5,
				(group, s) -> expected.get(group));
	}

	private static Map<TemperatureSensitivity, Integer> expected(int heat, int normal, int cold) {
		Map<TemperatureSensitivity, Integer> map = new EnumMap<>(TemperatureSensitivity.class);
		map.put(TemperatureSensitivity.HEAT_SENSITIVE, heat);
		map.put(TemperatureSensitivity.NORMAL, normal);
		map.put(TemperatureSensitivity.COLD_SENSITIVE, cold);
		return map;
	}

	/** BR-FEL-17 규칙 2: 가중 합 min-records(20) 미만이면 판정하지 않는다. 피드백은 0.5로 센다(경계값). */
	@Test
	void calibrationNeedsMinWeightedRecords() {
		Map<TemperatureSensitivity, Integer> exp = expected(3, 3, 2);
		assertThat(judge(repeat(19, sample(TemperatureSensitivity.NORMAL, AUTUMN, 2, null, FeelingRecordSource.DIRECT, 13.5)), exp))
			.isEmpty();
		assertThat(judge(repeat(20, sample(TemperatureSensitivity.NORMAL, AUTUMN, 2, null, FeelingRecordSource.DIRECT, 13.5)), exp))
			.hasValueSatisfying(o -> assertThat(o.suggested()).isEqualTo(TemperatureSensitivity.COLD_SENSITIVE));
		assertThat(judge(repeat(39, sample(TemperatureSensitivity.NORMAL, AUTUMN, 2, null, FeelingRecordSource.FEEDBACK, 13.5)), exp))
			.isEmpty();
		assertThat(judge(repeat(40, sample(TemperatureSensitivity.NORMAL, AUTUMN, 2, null, FeelingRecordSource.FEEDBACK, 13.5)), exp))
			.isPresent();
	}

	/** BR-FEL-17 규칙 4·5: (현재 오차 − 최소 오차) ≥ min-margin(0.5)일 때만 제안(경계 포함). 현재 그룹이 최소면 제안 없음. */
	@Test
	void calibrationMarginBoundary() {
		Map<TemperatureSensitivity, Integer> exp = expected(3, 3, 2);
		// 2단계 15건 + 3단계 5건: 보통 오차 0.75, 추위 0.25 → 차 0.5 → 제안
		List<MappingSample> atMargin = new ArrayList<>(
				repeat(15, sample(TemperatureSensitivity.NORMAL, AUTUMN, 2, null, FeelingRecordSource.DIRECT, 13.5)));
		atMargin.addAll(repeat(5, sample(TemperatureSensitivity.NORMAL, AUTUMN, 3, null, FeelingRecordSource.DIRECT, 13.5)));
		CalibrationJudgement.Outcome outcome = judge(atMargin, exp).orElseThrow();
		assertThat(outcome.errors().get(TemperatureSensitivity.NORMAL)).isEqualTo(0.75);
		assertThat(outcome.errors().get(TemperatureSensitivity.COLD_SENSITIVE)).isEqualTo(0.25);
		assertThat(outcome.suggested()).isEqualTo(TemperatureSensitivity.COLD_SENSITIVE);

		// 2단계 14건 + 3단계 6건: 차 0.4 → 제안 없음(판정은 함)
		List<MappingSample> below = new ArrayList<>(
				repeat(14, sample(TemperatureSensitivity.NORMAL, AUTUMN, 2, null, FeelingRecordSource.DIRECT, 13.5)));
		below.addAll(repeat(6, sample(TemperatureSensitivity.NORMAL, AUTUMN, 3, null, FeelingRecordSource.DIRECT, 13.5)));
		assertThat(judge(below, exp)).hasValueSatisfying(o -> assertThat(o.suggested()).isNull());

		// 현재 그룹이 가장 잘 맞음 → 제안 없음
		assertThat(judge(repeat(20, sample(TemperatureSensitivity.NORMAL, AUTUMN, 3, null, FeelingRecordSource.DIRECT, 13.5)), exp))
			.hasValueSatisfying(o -> assertThat(o.suggested()).isNull());
	}

}
