package kr.weathertalk.feeling.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import kr.weathertalk.feeling.TemperatureTag;
import kr.weathertalk.feeling.internal.config.FeelingProperties;
import kr.weathertalk.member.TemperatureSensitivity;

/**
 * 표·계산식 규칙: 대표 단계 가중 중앙값(BR-FEL-09), 고정 기준표(BR-FEL-18), 어제와 비교 문구(BR-FEL-16).
 */
class FeelingRulesTests {

	static final FeelingProperties.ReferenceTable TABLE = new FeelingProperties.ReferenceTable(
			List.of(29.0, 25.0, 22.0, 17.0, 12.0, 6.0), List.of(32.0, 27.0, 23.0, 17.0, 13.0, 8.0),
			List.of(35.0, 29.0, 24.0, 17.0, 14.0, 10.0));

	final ReferenceTable referenceTable = new ReferenceTable(TABLE);

	/** legacy 03-#11: 대표 단계는 가중 중앙값. 최다 선택이 빠지지 않고, 누적이 절반을 처음 넘는 단계다. */
	@Test
	void legacy03_11_representativeIsWeightedMedianNotTrimmedMode() {
		// 현행은 최빈값 정렬 후 마지막(최다)을 빼서 최다 선택이 사라졌다. 여기서는 최다(2단계 3건)가 그대로 대표다.
		assertThat(WeightedMedian.of(Map.of(2, 3.0, 5, 1.0), 4)).isEqualTo(2);
		assertThat(WeightedMedian.of(Map.of(1, 1.0, 3, 1.0, 6, 1.0), 4)).isEqualTo(3);
		assertThat(WeightedMedian.of(Map.of(1, 2.0, 7, 3.0), 4)).isEqualTo(7);
		// 표본 없음
		assertThat(WeightedMedian.of(Map.of(), 4)).isNull();
		assertThat(WeightedMedian.of(Map.of(3, 0.0), 4)).isNull();
	}

	/** BR-FEL-09: 누적이 정확히 절반이면 그 단계와 다음 단계 중 중립에 가까운 쪽(동률 처리를 결과로 고정). */
	@Test
	void exactHalfPicksTheSideNearerToNeutral() {
		// 온도(중립 4): 2단계 2건, 6단계 2건 → 2·6 모두 중립에서 2 → 낮은 쪽
		assertThat(WeightedMedian.of(Map.of(2, 2.0, 6, 2.0), 4)).isEqualTo(2);
		// 온도: 1단계 1건, 5단계 1건 → 5가 중립에 더 가깝다
		assertThat(WeightedMedian.of(Map.of(1, 1.0, 5, 1.0), 4)).isEqualTo(5);
		// 온도: 3단계 1건, 7단계 1건 → 3
		assertThat(WeightedMedian.of(Map.of(3, 1.0, 7, 1.0), 4)).isEqualTo(3);
		// 바람(중립 1): 2단계 1건, 3단계 1건 → 2
		assertThat(WeightedMedian.of(Map.of(2, 1.0, 3, 1.0), 1)).isEqualTo(2);
	}

	/** BR-FEL-10: 가중치가 대표 단계를 바꾼다(피드백 가중치 0.5면 피드백 2건 = 직접 1건). */
	@Test
	void weightsChangeTheMedian() {
		assertThat(WeightedMedian.of(Map.of(2, 1.0, 6, 2.0), 4)).isEqualTo(6);
		assertThat(WeightedMedian.of(Map.of(2, 1.0, 6, 1.0 * 0.5 * 2), 4)).isEqualTo(2);
		assertThat(WeightedMedian.of(Map.of(2, 1.0, 6, 0.5), 4)).isEqualTo(2);
	}

	/** legacy 03-#13: 기준표 경계값 — 경계는 "이상", 위 단계부터. 그룹마다 경계가 다르다(BR-FEL-18). */
	@ParameterizedTest
	@CsvSource({
			// 기온, 더위를 많이 탐, 보통, 추위를 많이 탐
			"35.0, 7, 7, 7", "34.9, 7, 7, 6", "32.0, 7, 7, 6", "31.9, 7, 6, 6", "29.0, 7, 6, 6", "28.9, 6, 6, 5",
			"27.0, 6, 6, 5", "26.9, 6, 5, 5", "25.0, 6, 5, 5", "24.9, 5, 5, 5", "24.0, 5, 5, 5", "23.9, 5, 5, 4",
			"23.0, 5, 5, 4", "22.9, 5, 4, 4", "22.0, 5, 4, 4", "21.9, 4, 4, 4", "17.0, 4, 4, 4", "16.9, 3, 3, 3",
			"14.0, 3, 3, 3", "13.9, 3, 3, 2", "13.0, 3, 3, 2", "12.9, 3, 2, 2", "12.0, 3, 2, 2", "11.9, 2, 2, 2",
			"10.0, 2, 2, 2", "9.9, 2, 2, 1", "8.0, 2, 2, 1", "7.9, 2, 1, 1", "6.0, 2, 1, 1", "5.9, 1, 1, 1",
			"-20.0, 1, 1, 1" })
	void legacy03_13_referenceTableBoundaries(double temperature, int heat, int normal, int cold) {
		assertThat(referenceTable.tagFor(TemperatureSensitivity.HEAT_SENSITIVE, temperature).level()).isEqualTo(heat);
		assertThat(referenceTable.tagFor(TemperatureSensitivity.NORMAL, temperature).level()).isEqualTo(normal);
		assertThat(referenceTable.tagFor(TemperatureSensitivity.COLD_SENSITIVE, temperature).level()).isEqualTo(cold);
	}

	/** legacy 03-#13: 기온이 오르면 태그가 내려가지 않고(단조), 추위를 많이 탈수록 같은 기온에서 더 춥거나 같다. */
	@Test
	void legacy03_13_referenceTableIsMonotonic() {
		for (TemperatureSensitivity s : TemperatureSensitivity.values()) {
			int previous = 1;
			for (double t = -20; t <= 40; t += 0.5) {
				int level = referenceTable.tagFor(s, t).level();
				assertThat(level).isGreaterThanOrEqualTo(previous);
				previous = level;
			}
		}
		for (double t = -20; t <= 40; t += 0.5) {
			assertThat(referenceTable.tagFor(TemperatureSensitivity.HEAT_SENSITIVE, t).level())
				.isGreaterThanOrEqualTo(referenceTable.tagFor(TemperatureSensitivity.NORMAL, t).level());
			assertThat(referenceTable.tagFor(TemperatureSensitivity.NORMAL, t).level())
				.isGreaterThanOrEqualTo(referenceTable.tagFor(TemperatureSensitivity.COLD_SENSITIVE, t).level());
		}
	}

	/** legacy 03-#13: 경계 순서가 단계 순서와 어긋나는 설정은 거부된다(설정 검증). */
	@Test
	void legacy03_13_nonMonotonicTableIsRejected() {
		assertThat(TABLE.isMonotonic()).isTrue();
		assertThat(new FeelingProperties.ReferenceTable(List.of(29.0, 25.0, 26.0, 17.0, 12.0, 6.0), TABLE.normal(),
				TABLE.coldSensitive()).isMonotonic()).isFalse();
		assertThat(new FeelingProperties.ReferenceTable(TABLE.heatSensitive(), List.of(32.0, 27.0, 23.0, 17.0, 17.0, 8.0),
				TABLE.coldSensitive()).isMonotonic()).isFalse();
	}

	/** BR-FEL-16 비교 문구 표: 방향은 오늘 단계 기준, 2단계 이상이면 "훨씬". */
	@ParameterizedTest
	@CsvSource({ "4, 4, 어제와 비슷해요", "1, 2, 어제보다 조금 더 추워요", "1, 3, 어제보다 훨씬 더 추워요",
			"3, 4, 어제보다 조금 더 추워요", "4, 5, 어제보다 조금 덜 더워요", "5, 7, 어제보다 훨씬 덜 더워요",
			"4, 3, 어제보다 조금 덜 추워요", "4, 1, 어제보다 훨씬 덜 추워요", "5, 4, 어제보다 조금 더 더워요",
			"7, 4, 어제보다 훨씬 더 더워요" })
	void yesterdayComparisonPhrase(int today, int yesterday, String phrase) {
		assertThat(ComparisonPhrase.of(TemperatureTag.of(today), TemperatureTag.of(yesterday), 2)).isEqualTo(phrase);
	}

}
