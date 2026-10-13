package kr.weathertalk.feeling;

import static kr.weathertalk.neighborhood.NeighborhoodTestData.N1;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N2;
import static kr.weathertalk.weather.WeatherStubs.kst;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.jayway.jsonpath.JsonPath;

import kr.weathertalk.AuthTestClient;
import kr.weathertalk.IntegrationTest;
import kr.weathertalk.feeling.internal.FeelingMappingRecalculation;
import kr.weathertalk.member.TemperatureSensitivity;

/**
 * 체감 매핑 재계산 (FR-FEL-06, BR-FEL-13~15)을 합성 데이터로: 매핑 생성, 미확보·기간 밖 기록 제외(BR-FEL-05, M3에서 옮긴 완료 조건),
 * 단조성 거부, 버전 전환(적용 중 → 보관), 보관 개수 초과 폐기, 적중률 집계, 그리고 기능 플래그에 따른 민감도별 체감 산출 순서.
 * 매핑 표본은 동네 체감 창에 들지 않게 하루 전 N2에 넣는다. 현재 = 2026-10-13 09:15(가을).
 */
@IntegrationTest
class FeelingMappingTests extends FeelingTestBase {

	static final Instant NOW = kst("2026-10-13T09:15");

	@Autowired
	FeelingMappingRecalculation recalculation;

	/** 관측 스냅샷 '확보'(습도 60%, 풍속 2.0 m/s) 표본 n건. */
	private void samples(int n, TemperatureSensitivity sensitivity, int level, double temperature,
			FeelingRecordSource source) {
		for (int i = 0; i < n; i++) {
			insertRecord(N2, sensitivity, NOW.minus(Duration.ofDays(1)).minus(Duration.ofMinutes(i)), level, null, null,
					source, null, temperature);
		}
	}

	private void monotonicNormalSamples() {
		samples(30, TemperatureSensitivity.NORMAL, 3, 10.0, FeelingRecordSource.DIRECT);
		samples(30, TemperatureSensitivity.NORMAL, 5, 20.0, FeelingRecordSource.DIRECT);
		samples(30, TemperatureSensitivity.NORMAL, 6, 26.0, FeelingRecordSource.DIRECT);
	}

	private String status(int version) {
		return jdbc.sql("SELECT status FROM feeling_mapping_version WHERE version = :v")
			.param("v", version)
			.query(String.class)
			.single();
	}

	private List<Map<String, Object>> coarseCells(int version) {
		return jdbc.sql("SELECT temperature_bin, temperature_level, sample_count FROM feeling_mapping_cell "
				+ "WHERE version = :v AND layer = 'COARSE' ORDER BY temperature_bin")
			.param("v", version)
			.query()
			.listOfRows();
	}

	private int cellCount(int version) {
		return jdbc.sql("SELECT count(*) FROM feeling_mapping_cell WHERE version = :v")
			.param("v", version)
			.query(Integer.class)
			.single();
	}

	/**
	 * 확보 기록으로만 셀을 만든다: 미확보 기록(값이 일부 있어도)과 feeling.mapping.lookback(365일) 밖 기록은 빠진다(BR-FEL-05, FR-FEL-06 입력).
	 * 셀은 세 층 모두, 각 셀 최소 표본 30.
	 */
	@Test
	void recalculationUsesSecuredRecordsOnlyAndAppliesVersion() {
		clock.set(NOW);
		monotonicNormalSamples();
		// 미확보: 30℃ 7단계 40건, 20℃ 1단계 40건 — 스냅샷 값은 있으나 상태가 미확보
		samples(40, TemperatureSensitivity.NORMAL, 7, 30.0, FeelingRecordSource.DIRECT);
		samples(40, TemperatureSensitivity.NORMAL, 1, 20.5, FeelingRecordSource.DIRECT);
		jdbc.sql("UPDATE feeling_record SET snapshot_status = 'MISSING' WHERE temperature_level IN (1, 7)").update();
		// 기간 밖: 400일 전 30℃ 40건
		for (int i = 0; i < 40; i++) {
			insertRecord(N2, TemperatureSensitivity.NORMAL, NOW.minus(Duration.ofDays(400)), 7, null, null,
					FeelingRecordSource.DIRECT, null, 30.0);
		}

		FeelingMappingRecalculation.Result result = recalculation.recalculate();

		assertThat(result.status()).isEqualTo("APPLIED");
		assertThat(status(result.version())).isEqualTo("APPLIED");
		// 기온 구간: 10℃ = 13, 20℃ = 18, 26℃ = 21. 30℃(23) 셀은 없다
		assertThat(coarseCells(result.version())).extracting(row -> ((Number) row.get("temperature_bin")).intValue(),
				row -> ((Number) row.get("temperature_level")).intValue(),
				row -> ((Number) row.get("sample_count")).intValue())
			.containsExactly(org.assertj.core.groups.Tuple.tuple(13, 3, 30), org.assertj.core.groups.Tuple.tuple(18, 5, 30),
					org.assertj.core.groups.Tuple.tuple(21, 6, 30));
		assertThat(cellCount(result.version())).isEqualTo(9); // 세부·계절 없음·축약 각 3
		String validation = jdbc.sql("SELECT validation::text FROM feeling_mapping_version WHERE version = :v")
			.param("v", result.version())
			.query(String.class)
			.single();
		assertThat((Integer) JsonPath.read(validation, "$.sampleCount")).isEqualTo(90);
		String settings = jdbc.sql("SELECT settings::text FROM feeling_mapping_version WHERE version = :v")
			.param("v", result.version())
			.query(String.class)
			.single();
		assertThat((List<Integer>) JsonPath.read(settings, "$.seasons.WINTER")).containsExactly(12, 1, 2);
		assertThat((Integer) JsonPath.read(settings, "$.minSamples")).isEqualTo(30);
	}

	/** 버전 전환: 검증 실패 버전은 '거부'되고 이전 적용 버전이 유지된다. 다음 통과 버전이 '적용 중', 이전 것은 '보관'. */
	@Test
	void rejectedVersionKeepsPreviousAndNextVersionArchivesIt() {
		clock.set(NOW);
		monotonicNormalSamples();
		int v1 = recalculation.recalculate().version();
		assertThat(status(v1)).isEqualTo("APPLIED");

		// 추위를 많이 탐: 기온이 오를수록 단계가 내려간다 → 축약 셀 6개 중 2개를 비워야 함(33% > 20%)
		samples(30, TemperatureSensitivity.COLD_SENSITIVE, 6, 10.0, FeelingRecordSource.DIRECT);
		samples(30, TemperatureSensitivity.COLD_SENSITIVE, 3, 20.0, FeelingRecordSource.DIRECT);
		samples(30, TemperatureSensitivity.COLD_SENSITIVE, 2, 26.0, FeelingRecordSource.DIRECT);
		clock.set(NOW.plus(Duration.ofHours(1)));
		FeelingMappingRecalculation.Result rejected = recalculation.recalculate();
		assertThat(rejected.status()).isEqualTo("REJECTED");
		assertThat(rejected.rejectReason()).isEqualTo("TOO_MANY_MONOTONICITY_VIOLATIONS");
		assertThat(status(rejected.version())).isEqualTo("REJECTED");
		assertThat(cellCount(rejected.version())).isZero();
		assertThat(status(v1)).isEqualTo("APPLIED");

		jdbc.sql("DELETE FROM feeling_record WHERE temperature_sensitivity = 'COLD_SENSITIVE'").update();
		clock.set(NOW.plus(Duration.ofHours(2)));
		FeelingMappingRecalculation.Result next = recalculation.recalculate();
		assertThat(next.status()).isEqualTo("APPLIED");
		assertThat(next.version()).isGreaterThan(rejected.version());
		assertThat(status(v1)).isEqualTo("ARCHIVED");
		assertThat(cellCount(v1)).isEqualTo(9); // 보관 버전은 되돌리기용으로 셀을 남긴다
		assertThat(jdbc.sql("SELECT count(*) FROM feeling_mapping_version WHERE status = 'APPLIED'")
			.query(Integer.class)
			.single()).isEqualTo(1);
	}

	/** 보관 버전이 feeling.mapping.retained-versions(10)를 넘으면 오래된 것부터 '폐기'(셀 삭제, 요약은 남김). */
	@Test
	void archivedVersionsBeyondRetainedAreDiscarded() {
		clock.set(NOW);
		monotonicNormalSamples();
		int first = recalculation.recalculate().version();
		int last = first;
		for (int i = 1; i < 12; i++) {
			clock.set(NOW.plus(Duration.ofMinutes(i)));
			last = recalculation.recalculate().version();
		}
		assertThat(status(first)).isEqualTo("DISCARDED");
		assertThat(cellCount(first)).isZero();
		assertThat(status(first + 1)).isEqualTo("ARCHIVED");
		assertThat(status(last)).isEqualTo("APPLIED");
		assertThat(jdbc.sql("SELECT count(*) FROM feeling_mapping_version WHERE status = 'ARCHIVED'")
			.query(Integer.class)
			.single()).isEqualTo(10);
	}

	/** 적중률(FR-FEL-06 규칙 5): 직전 실행 이후 피드백의 산출 출처·매핑 버전별 '맞아요' 비율과 응답 분포를 새 버전에 남긴다. */
	@Test
	void hitRateIsAggregatedPerSourceAndVersionSinceLastRun() {
		clock.set(NOW);
		monotonicNormalSamples();
		int v1 = recalculation.recalculate().version();

		// v1 이후 피드백: 체감 매핑(v1)으로 보여준 값에 맞아요 2·더 추워요 1, 기준표로 보여준 값에 더 더워요 1
		Instant later = NOW.plus(Duration.ofHours(3));
		List<UUID> ids = List.of(
				insertRecord(N1, TemperatureSensitivity.NORMAL, later.minusSeconds(10), 4, null, null,
						FeelingRecordSource.FEEDBACK, null, 20.0),
				insertRecord(N1, TemperatureSensitivity.NORMAL, later.minusSeconds(20), 4, null, null,
						FeelingRecordSource.FEEDBACK, null, 20.0),
				insertRecord(N1, TemperatureSensitivity.NORMAL, later.minusSeconds(30), 3, null, null,
						FeelingRecordSource.DIRECT_WITH_FEEDBACK, null, 20.0));
		jdbc.sql("UPDATE feeling_record SET feedback_shown_source = 'FEELING_MAPPING', feedback_shown_mapping_version = :v "
				+ "WHERE id IN (:ids)")
			.param("v", v1)
			.param("ids", ids)
			.update();
		jdbc.sql("UPDATE feeling_record SET feedback_answer = 'COLDER' WHERE id = :id").param("id", ids.get(2)).update();
		UUID reference = insertRecord(N1, TemperatureSensitivity.HEAT_SENSITIVE, later.minusSeconds(40), 5, null, null,
				FeelingRecordSource.FEEDBACK, null, 20.0);
		jdbc.sql("UPDATE feeling_record SET feedback_answer = 'HOTTER' WHERE id = :id").param("id", reference).update();
		// v1 이전(이미 집계한 구간) 피드백은 다시 세지 않는다
		insertRecord(N1, TemperatureSensitivity.NORMAL, NOW.minusSeconds(60), 4, null, null, FeelingRecordSource.FEEDBACK,
				null, 20.0);

		clock.set(later);
		int v2 = recalculation.recalculate().version();
		String hitRate = jdbc.sql("SELECT hit_rate::text FROM feeling_mapping_version WHERE version = :v")
			.param("v", v2)
			.query(String.class)
			.single();
		assertThat((Integer) JsonPath.read(hitRate, "$.length()")).isEqualTo(2);
		assertThat((String) JsonPath.read(hitRate, "$[0].shownSource")).isEqualTo("FEELING_MAPPING");
		assertThat((Integer) JsonPath.read(hitRate, "$[0].shownMappingVersion")).isEqualTo(v1);
		assertThat((Integer) JsonPath.read(hitRate, "$[0].total")).isEqualTo(3);
		assertThat((Integer) JsonPath.read(hitRate, "$[0].right")).isEqualTo(2);
		assertThat((Integer) JsonPath.read(hitRate, "$[0].colder")).isEqualTo(1);
		assertThat((Double) JsonPath.read(hitRate, "$[0].rightRatio")).isCloseTo(2.0 / 3,
				org.assertj.core.data.Offset.offset(1e-9));
		assertThat((String) JsonPath.read(hitRate, "$[1].shownSource")).isEqualTo("REFERENCE_TABLE");
		assertThat((Integer) JsonPath.read(hitRate, "$[1].hotter")).isEqualTo(1);
		assertThat((Double) JsonPath.read(hitRate, "$[1].rightRatio")).isZero();
	}

	/**
	 * 기능 플래그(feeling.mapping.enabled): 꺼져 있으면 적용 중인 매핑이 있어도 동네 체감 → 기준표. 켜면 동네 체감 → 체감 매핑 → 기준표 순이고,
	 * 매핑 결과에 산출 출처·매핑 버전·셀 층·근거 표본 수가 담긴다(BR-FEL-13). 셀이 없는 그룹은 기준표로 내려간다.
	 */
	@Test
	void derivationOrderFollowsMappingFlag() {
		clock.set(NOW);
		samples(30, TemperatureSensitivity.NORMAL, 5, 20.0, FeelingRecordSource.DIRECT);
		int version = recalculation.recalculate().version();
		stubs.observation(gridOf(N1), kst("2026-10-13T09:00"), 20.0); // 습도 60%, 풍속 2.0 → 세부 셀과 같은 구간

		// 플래그 꺼짐(기본): 기준표
		SensitivityFeeling off = feelingApi.findSensitivityFeeling(N1, TemperatureSensitivity.NORMAL, null).orElseThrow();
		assertThat(off.source()).isEqualTo(DerivationSource.REFERENCE_TABLE);
		assertThat(off.temperatureTag()).isEqualTo(TemperatureTag.JUST_RIGHT);
		assertThat(off.mappingVersion()).isNull();
		assertThat(off.mappingLayer()).isNull();

		// 플래그 켬: 체감 매핑
		mappingFlag.set(true);
		SensitivityFeelings on = feelingApi.findSensitivityFeelings(N1, null);
		SensitivityFeeling normal = on.find(TemperatureSensitivity.NORMAL).orElseThrow();
		assertThat(normal.source()).isEqualTo(DerivationSource.FEELING_MAPPING);
		assertThat(normal.temperatureTag()).isEqualTo(TemperatureTag.SLIGHTLY_HOT);
		assertThat(normal.mappingVersion()).isEqualTo(version);
		assertThat(normal.mappingLayer()).isEqualTo(MappingLayer.DETAIL);
		assertThat(normal.sampleCount()).isEqualTo(30);
		assertThat(normal.weatherBasedTemperatureTag()).isEqualTo(TemperatureTag.JUST_RIGHT); // 기상값 기준은 항상 기준표
		assertThat(on.find(TemperatureSensitivity.HEAT_SENSITIVE).orElseThrow().source())
			.isEqualTo(DerivationSource.REFERENCE_TABLE); // 그 그룹 셀 없음

		// 동네 체감이 충분하면 1단계가 먼저
		for (int i = 0; i < 5; i++) {
			insertRecord(N1, TemperatureSensitivity.NORMAL, NOW.minus(Duration.ofMinutes(5 + i)), 2);
		}
		SensitivityFeeling neighborhood = feelingApi.findSensitivityFeeling(N1, TemperatureSensitivity.NORMAL, null)
			.orElseThrow();
		assertThat(neighborhood.source()).isEqualTo(DerivationSource.NEIGHBORHOOD_FEELING);
		assertThat(neighborhood.temperatureTag()).isEqualTo(TemperatureTag.COLD);

		// HTTP 응답에도 매핑 버전·셀 층이 실린다(피드백 때 앱이 그대로 돌려보냄)
		jdbc.sql("DELETE FROM feeling_record WHERE neighborhood_code = :n").param("n", N1.value()).update();
		try {
			mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.get("/api/v1/feeling/neighborhoods/{id}/sensitivity-feelings", N1.value())
				.param("temperatureSensitivity", "NORMAL"))
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.groups[0].source")
					.value("FEELING_MAPPING"))
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
					.jsonPath("$.groups[0].mappingVersion")
					.value(version))
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
					.jsonPath("$.groups[0].mappingLayer")
					.value("DETAIL"));
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	/** 내 체감 패턴(FR-FEL-11)의 예상 단계도 플래그가 켜져 있으면 적용 중인 매핑, 꺼져 있으면 기준표로 구한다. */
	@Test
	void myPatternUsesActiveMappingForExpectedLevel() throws Exception {
		clock.set(NOW);
		samples(30, TemperatureSensitivity.NORMAL, 5, 20.0, FeelingRecordSource.DIRECT);
		recalculation.recalculate();
		AuthTestClient.Session session = signup(TemperatureSensitivity.NORMAL);
		for (int i = 0; i < 5; i++) {
			insertRecord(N1, TemperatureSensitivity.NORMAL, NOW.minus(Duration.ofDays(i + 1)), 5, null, null,
					FeelingRecordSource.DIRECT, session.memberId().value(), 20.0);
		}
		// 기준표(보통 20℃ = 4) 대비 +1 → 덥게 느끼는 편
		assertThat(feelingApi.findMyRecords(session.memberId()).pattern().tendency())
			.isEqualTo(MyFeelingRecords.Tendency.FEELS_HOTTER);
		mappingFlag.set(true);
		// 매핑(같은 조건 보통 그룹 = 5) 대비 0 → 비슷
		assertThat(feelingApi.findMyRecords(session.memberId()).pattern().tendency())
			.isEqualTo(MyFeelingRecords.Tendency.SIMILAR);
	}

}
