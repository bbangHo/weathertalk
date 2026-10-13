package kr.weathertalk.feeling;

import static kr.weathertalk.neighborhood.NeighborhoodTestData.N1;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N2;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N7;
import static kr.weathertalk.weather.WeatherStubs.N7_GRID;
import static kr.weathertalk.weather.WeatherStubs.kst;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import kr.weathertalk.IntegrationTest;
import kr.weathertalk.member.TemperatureSensitivity;
import kr.weathertalk.shared.error.DomainException;

/**
 * 동네 체감(FR-FEL-03)·민감도별 체감(FR-FEL-04, 산출 출처별)·어제와 비교(FR-FEL-05).
 * 표본은 행으로 직접 넣는다. 현재 = 2026-10-13 09:15, 동네 체감 창 = (07:15, 09:15].
 */
@IntegrationTest
class SensitivityFeelingTests extends FeelingTestBase {

	static final Instant NOW = kst("2026-10-13T09:15");

	private void stubN1Observation(double temperature) {
		stubs.observation(gridOf(N1), kst("2026-10-13T09:00"), temperature);
	}

	private void insertGroup(TemperatureSensitivity sensitivity, int count, int temperature) {
		for (int i = 0; i < count; i++) {
			insertRecord(N1, sensitivity, NOW.minus(Duration.ofMinutes(10 + i)), temperature);
		}
	}

	/** 산출 출처 = 고정 기준표: 기록이 없으면 그 시각 기온과 그룹 경계로(BR-FEL-13 3단계). 기상값 기준 태그를 항상 담는다. */
	@Test
	void referenceTableIsUsedWithoutNeighborhoodRecords() {
		clock.set(NOW);
		stubN1Observation(23.5);

		SensitivityFeelings feelings = feelingApi.findSensitivityFeelings(N1, null);
		assertThat(feelings.isComplete()).isTrue();
		assertThat(feelings.basisTime()).isEqualTo(kst("2026-10-13T09:00"));
		assertThat(feelings.groups()).extracting(SensitivityFeeling::temperatureSensitivity)
			.containsExactly(TemperatureSensitivity.HEAT_SENSITIVE, TemperatureSensitivity.NORMAL,
					TemperatureSensitivity.COLD_SENSITIVE);
		assertThat(feelings.groups()).allSatisfy(f -> {
			assertThat(f.source()).isEqualTo(DerivationSource.REFERENCE_TABLE);
			assertThat(f.timeKind()).isEqualTo(SensitivityFeeling.TimeKind.CURRENT);
			assertThat(f.weatherBasedTemperatureTag()).isEqualTo(f.temperatureTag());
			assertThat(f.humidityTag()).isNull();
			assertThat(f.windTag()).isNull();
			assertThat(f.derivedAt()).isEqualTo(NOW);
		});
		assertThat(feelings.find(TemperatureSensitivity.HEAT_SENSITIVE).orElseThrow().temperatureTag())
			.isEqualTo(TemperatureTag.SLIGHTLY_HOT);
		assertThat(feelings.find(TemperatureSensitivity.NORMAL).orElseThrow().temperatureTag())
			.isEqualTo(TemperatureTag.SLIGHTLY_HOT);
		assertThat(feelings.find(TemperatureSensitivity.COLD_SENSITIVE).orElseThrow().temperatureTag())
			.isEqualTo(TemperatureTag.JUST_RIGHT);
	}

	/**
	 * 산출 출처 = 동네 체감: 그룹 온도 표본이 feeling.min-group-records(5) 이상일 때만(경계값). 다른 그룹 기록은 세지 않는다.
	 * 습도·바람은 범주별로 같은 기준 이상일 때만 담는다.
	 */
	@Test
	void neighborhoodFeelingIsUsedFromMinGroupRecords() {
		clock.set(NOW);
		stubN1Observation(23.5);
		insertGroup(TemperatureSensitivity.COLD_SENSITIVE, 6, 1);
		insertGroup(TemperatureSensitivity.NORMAL, 4, 2);

		SensitivityFeeling four = feelingApi.findSensitivityFeeling(N1, TemperatureSensitivity.NORMAL, null)
			.orElseThrow();
		assertThat(four.source()).isEqualTo(DerivationSource.REFERENCE_TABLE);
		assertThat(four.temperatureTag()).isEqualTo(TemperatureTag.SLIGHTLY_HOT);

		insertRecord(N1, TemperatureSensitivity.NORMAL, NOW.minus(Duration.ofMinutes(1)), 2, 4, 2,
				FeelingRecordSource.DIRECT, null, null);
		SensitivityFeelings feelings = feelingApi.findSensitivityFeelings(N1, null);
		SensitivityFeeling normal = feelings.find(TemperatureSensitivity.NORMAL).orElseThrow();
		assertThat(normal.source()).isEqualTo(DerivationSource.NEIGHBORHOOD_FEELING);
		assertThat(normal.temperatureTag()).isEqualTo(TemperatureTag.COLD);
		assertThat(normal.sampleCount()).isEqualTo(5);
		assertThat(normal.weatherBasedTemperatureTag()).isEqualTo(TemperatureTag.SLIGHTLY_HOT);
		// 습도·바람은 1건뿐이라 담지 않는다
		assertThat(normal.humidityTag()).isNull();
		assertThat(normal.windTag()).isNull();
		assertThat(feelings.find(TemperatureSensitivity.COLD_SENSITIVE).orElseThrow().temperatureTag())
			.isEqualTo(TemperatureTag.VERY_COLD);
		assertThat(feelings.find(TemperatureSensitivity.HEAT_SENSITIVE).orElseThrow().source())
			.isEqualTo(DerivationSource.REFERENCE_TABLE);

		// 습도 표본 5건이 되면 습도도 담는다(바람은 여전히 1건)
		for (int i = 0; i < 4; i++) {
			insertRecord(N1, TemperatureSensitivity.NORMAL, NOW.minus(Duration.ofMinutes(2 + i)), 3, 4, null,
					FeelingRecordSource.DIRECT, null, null);
		}
		SensitivityFeeling withHumidity = feelingApi.findSensitivityFeeling(N1, TemperatureSensitivity.NORMAL, null)
			.orElseThrow();
		assertThat(withHumidity.humidityTag()).isEqualTo(HumidityTag.HUMID);
		assertThat(withHumidity.windTag()).isNull();
		assertThat(withHumidity.sampleCount()).isEqualTo(9);
	}

	/** BR-FEL-08: 집계 창은 (기준 시각 − 2시간, 기준 시각]. 창 밖 기록은 세지 않는다. 새 기록은 다음 조회에 바로 반영된다(BR-FEL-12). */
	@Test
	void neighborhoodWindowBoundary() {
		clock.set(NOW);
		stubN1Observation(23.5);
		for (int i = 0; i < 5; i++) {
			insertRecord(N1, TemperatureSensitivity.NORMAL, kst("2026-10-13T07:15"), 1);
		}
		assertThat(feelingApi.findSensitivityFeeling(N1, TemperatureSensitivity.NORMAL, null).orElseThrow().source())
			.isEqualTo(DerivationSource.REFERENCE_TABLE);
		insertRecord(N1, TemperatureSensitivity.NORMAL, kst("2026-10-13T07:16"), 1);
		assertThat(feelingApi.findNeighborhoodFeeling(N1, null).groups().get(TemperatureSensitivity.NORMAL)
			.temperature()
			.sampleCount()).isEqualTo(1);
		for (int i = 0; i < 4; i++) {
			insertRecord(N1, TemperatureSensitivity.NORMAL, NOW, 1);
		}
		assertThat(feelingApi.findSensitivityFeeling(N1, TemperatureSensitivity.NORMAL, null).orElseThrow().source())
			.isEqualTo(DerivationSource.NEIGHBORHOOD_FEELING);
	}

	/** BR-FEL-13: 미래 시각은 동네 체감 단계를 건너뛰고 시간별 예보의 그 시각 기온으로 산출한다. */
	@Test
	void futureTimeSkipsNeighborhoodStageAndUsesHourlyForecast() {
		clock.set(NOW);
		stubs.shortTerm(gridOf(N1), kst("2026-10-13T08:00"), 24,
				at -> at.getHour() == 13 ? Map.of("TMP", "30") : Map.of());
		insertGroup(TemperatureSensitivity.NORMAL, 6, 1);

		SensitivityFeeling future = feelingApi
			.findSensitivityFeeling(N1, TemperatureSensitivity.NORMAL, kst("2026-10-13T13:40"))
			.orElseThrow();
		assertThat(future.timeKind()).isEqualTo(SensitivityFeeling.TimeKind.FUTURE);
		assertThat(future.basisTime()).isEqualTo(kst("2026-10-13T13:00"));
		assertThat(future.source()).isEqualTo(DerivationSource.REFERENCE_TABLE);
		assertThat(future.temperatureTag()).isEqualTo(TemperatureTag.HOT);

		// 예보 범위 밖 미래는 산출 불가
		assertThat(feelingApi.findSensitivityFeeling(N1, TemperatureSensitivity.NORMAL, kst("2026-10-15T13:00")))
			.isEmpty();
	}

	/** FR-FEL-04 실패 케이스: 과거는 feeling.past-lookup-limit(48시간)까지, 동네 체감도 기온도 없으면 산출 불가(그 그룹만 빠짐). */
	@Test
	void pastLimitAndUnavailableGroups() throws Exception {
		clock.set(NOW);
		assertThatThrownBy(() -> feelingApi.findSensitivityFeelings(N1, NOW.minus(Duration.ofHours(49))))
			.isInstanceOf(DomainException.class);
		mvc.perform(get("/api/v1/feeling/neighborhoods/{id}/sensitivity-feelings", N1.value()).param("at",
				"2026-10-11T08:00:00+09:00"))
			.andExpect(status().isBadRequest());

		// 관측 없음 + 기록 없음 → 세 그룹 모두 산출 불가(오류가 아니라 빈 결과)
		assertThat(feelingApi.findSensitivityFeelings(N1, null).groups()).isEmpty();
		// 보통 그룹만 동네 체감이 있으면 그 그룹만 산출된다(기상값 기준 태그는 비어 있음)
		insertGroup(TemperatureSensitivity.NORMAL, 5, 6);
		SensitivityFeelings partial = feelingApi.findSensitivityFeelings(N1, null);
		assertThat(partial.groups()).singleElement().satisfies(f -> {
			assertThat(f.temperatureSensitivity()).isEqualTo(TemperatureSensitivity.NORMAL);
			assertThat(f.source()).isEqualTo(DerivationSource.NEIGHBORHOOD_FEELING);
			assertThat(f.weatherBasedTemperatureTag()).isNull();
		});
		mvc.perform(get("/api/v1/feeling/neighborhoods/{id}/sensitivity-feelings", N1.value())
			.param("temperatureSensitivity", "NORMAL"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.groups[0].temperatureTag").value("HOT"))
			.andExpect(jsonPath("$.groups[0].source").value("NEIGHBORHOOD_FEELING"));
		mvc.perform(get("/api/v1/feeling/neighborhoods/{id}/sensitivity-feelings", "0000000000"))
			.andExpect(status().isNotFound());
	}

	/**
	 * legacy 03-#12: 기록 부족은 오류가 아니다 — 비회원도 조회하며, 범주·그룹별 부분 결과(기록 없음 / 기록 부족 / 대표 단계)를 돌려준다.
	 * legacy 03-#11: 분포는 모든 단계를 담고 대표 단계는 가중 중앙값이다. 탈퇴로 익명화된 기록도 집계한다.
	 */
	@Test
	void legacy03_12_neighborhoodFeelingReturnsPartialResultsForGuests() throws Exception {
		clock.set(NOW);
		mvc.perform(get("/api/v1/feeling/neighborhoods/{id}", N2.value()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.overall.temperature.status").value("NO_RECORDS"))
			.andExpect(jsonPath("$.overall.temperature.sampleCount").value(0))
			.andExpect(jsonPath("$.overall.temperature.distribution.VERY_COLD").value(0))
			.andExpect(jsonPath("$.groups.NORMAL.humidity.status").value("NO_RECORDS"));

		insertRecord(N2, TemperatureSensitivity.NORMAL, NOW.minusSeconds(60), 2, 2, null, FeelingRecordSource.DIRECT,
				null, null);
		insertRecord(N2, TemperatureSensitivity.NORMAL, NOW.minusSeconds(120), 2, null, null,
				FeelingRecordSource.FEEDBACK, UUID.randomUUID(), null);
		insertRecord(N2, TemperatureSensitivity.COLD_SENSITIVE, NOW.minusSeconds(180), 6, null, null,
				FeelingRecordSource.DIRECT_WITH_FEEDBACK, null, null);

		mvc.perform(get("/api/v1/feeling/neighborhoods/{id}", N2.value()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.overall.temperature.status").value("OK"))
			.andExpect(jsonPath("$.overall.temperature.sampleCount").value(3))
			.andExpect(jsonPath("$.overall.temperature.representative").value("COLD"))
			.andExpect(jsonPath("$.overall.temperature.distribution.COLD").value(2))
			.andExpect(jsonPath("$.overall.temperature.distribution.HOT").value(1))
			.andExpect(jsonPath("$.overall.humidity.status").value("INSUFFICIENT_RECORDS"))
			.andExpect(jsonPath("$.overall.humidity.sampleCount").value(1))
			.andExpect(jsonPath("$.overall.humidity.representative").doesNotExist())
			.andExpect(jsonPath("$.overall.wind.status").value("NO_RECORDS"))
			.andExpect(jsonPath("$.groups.NORMAL.temperature.status").value("INSUFFICIENT_RECORDS"))
			.andExpect(jsonPath("$.groups.NORMAL.temperature.sampleCount").value(2))
			.andExpect(jsonPath("$.groups.HEAT_SENSITIVE.temperature.status").value("NO_RECORDS"));

		mvc.perform(get("/api/v1/feeling/neighborhoods/{id}", "0000000000")).andExpect(status().isNotFound());
	}

	/**
	 * FR-FEL-05·BR-FEL-16: 오늘 T와 어제 T − 24시간을 같은 방식으로 산출해 비교한다(과거는 과거 관측으로). 어제 값을 얻지 못하면 비교 불가.
	 */
	@Test
	void yesterdayComparisonUsesPastObservation() throws Exception {
		// 어제 09시 관측(10℃)을 먼저 수집해 둔다(과거 관측 조회는 저장분만 쓴다)
		clock.set(kst("2026-10-12T09:15"));
		stubs.observation(gridOf(N1), kst("2026-10-12T09:00"), 10.0);
		weatherApi.findCurrentObservation(N1);

		clock.set(NOW);
		stubN1Observation(18.0);
		YesterdayComparison comparison = feelingApi.compareWithYesterday(N1, TemperatureSensitivity.NORMAL, null);
		assertThat(comparison.comparable()).isTrue();
		assertThat(comparison.today().temperatureTag()).isEqualTo(TemperatureTag.JUST_RIGHT);
		assertThat(comparison.yesterday().temperatureTag()).isEqualTo(TemperatureTag.COLD);
		assertThat(comparison.yesterday().timeKind()).isEqualTo(SensitivityFeeling.TimeKind.PAST);
		assertThat(comparison.yesterday().basisTime()).isEqualTo(kst("2026-10-12T09:00"));
		assertThat(comparison.levelDifference()).isEqualTo(2);
		assertThat(comparison.phrase()).isEqualTo("어제보다 훨씬 덜 추워요");

		// 어제 동네 체감이 있으면 어제는 동네 체감으로(출처가 달라도 비교)
		for (int i = 0; i < 5; i++) {
			insertRecord(N1, TemperatureSensitivity.NORMAL, kst("2026-10-12T09:00").plusSeconds(i), 3);
		}
		YesterdayComparison mixed = feelingApi.compareWithYesterday(N1, TemperatureSensitivity.NORMAL, null);
		assertThat(mixed.yesterday().source()).isEqualTo(DerivationSource.NEIGHBORHOOD_FEELING);
		assertThat(mixed.today().source()).isEqualTo(DerivationSource.REFERENCE_TABLE);
		assertThat(mixed.phrase()).isEqualTo("어제보다 조금 덜 추워요");

		// 어제 관측이 없는 동네는 비교 불가(문구 없음)
		stubs.observation(N7_GRID, kst("2026-10-13T09:00"), 18.0);
		mvc.perform(get("/api/v1/feeling/neighborhoods/{id}/yesterday-comparison", N7.value())
			.param("temperatureSensitivity", "NORMAL"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.comparable").value(false))
			.andExpect(jsonPath("$.phrase").doesNotExist())
			.andExpect(jsonPath("$.today.temperatureTag").value("JUST_RIGHT"));
	}

}
