package kr.weathertalk.feeling;

import static kr.weathertalk.neighborhood.NeighborhoodTestData.AT_SEA;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.INSIDE_N1;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N1;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N2;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.NEAR_N2;
import static kr.weathertalk.weather.WeatherStubs.kst;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;

import kr.weathertalk.AuthTestClient;
import kr.weathertalk.IntegrationTest;
import kr.weathertalk.feeling.internal.FeelingRecordService;
import kr.weathertalk.member.TemperatureSensitivity;
import kr.weathertalk.neighborhood.NeighborhoodTestData;
import kr.weathertalk.shared.Ids.FeelingRecordId;
import kr.weathertalk.shared.idempotency.IdempotencyKey;

/**
 * 체감 기록·피드백 쓰기 (FR-FEL-01·02·10, BR-FEL-01~07).
 */
@IntegrationTest
class FeelingRecordTests extends FeelingTestBase {

	static final Instant NOW = kst("2026-10-13T09:15");

	@Autowired
	FeelingRecordService recordService;

	@Autowired
	ApplicationEvents events;

	private void stubN1Observation(double temperature) {
		stubs.observation(gridOf(N1), kst("2026-10-13T09:00"), temperature);
	}

	/** BR-FEL-01: 비회원은 가입 필요. contracts §4: 약관 재동의 전에는 기록할 수 없다. */
	@Test
	void guestAndMembersBeforeTermsReagreementCannotRecord() throws Exception {
		clock.set(NOW);
		mvc.perform(post("/api/v1/feeling/records").header(IdempotencyKey.HEADER, newKey())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"temperature\":\"COLD\"," + locationJson(INSIDE_N1) + "}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("SIGNUP_REQUIRED"));

		AuthTestClient.Session session = signup(TemperatureSensitivity.NORMAL);
		jdbc.sql("INSERT INTO member_terms (kind, version, title, required, major_revision, effective_at, content_url) "
				+ "VALUES ('SERVICE', 98, 'v98', true, true, '2026-01-02 00:00:00+09', 'about:blank')").update();
		try {
			record(session, "COLD").andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("TERMS_REAGREEMENT_REQUIRED"));
			// 조회(마이페이지 내 기록)는 재동의 전에도 허용
			mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.get("/api/v1/members/me/feeling-records")
				.header(HttpHeaders.AUTHORIZATION, session.bearer())).andExpect(status().isOk());
		}
		finally {
			jdbc.sql("DELETE FROM member_terms WHERE version = 98").update();
		}
		assertThat(recordCount()).isZero();
	}

	/** legacy 03-#6: 온도는 필수, 습도·바람은 선택. 누락·범위 밖 값은 4xx 검증 오류(500 아님). */
	@Test
	void legacy03_6_missingOrUnknownTagsAreValidationErrors() throws Exception {
		clock.set(NOW);
		AuthTestClient.Session session = signup(TemperatureSensitivity.NORMAL);
		record(session, newKey(), null, "DRY", null, INSIDE_N1).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		record(session, newKey(), "WARM", null, null, INSIDE_N1).andExpect(status().isBadRequest());
		record(session, newKey(), "COLD", "SLIGHTLY_HUMID", null, INSIDE_N1).andExpect(status().isBadRequest());
		record(session, newKey(), "COLD", null, "NORMAL", INSIDE_N1).andExpect(status().isBadRequest());
		mvc.perform(post("/api/v1/feeling/records").header(HttpHeaders.AUTHORIZATION, session.bearer())
			.header(IdempotencyKey.HEADER, newKey())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"temperature\":4," + locationJson(INSIDE_N1) + "}")).andExpect(status().isBadRequest());
		assertThat(recordCount()).isZero();

		// legacy 03-#4: 모든 단계 값으로 기록할 수 있다(바람 3단계 '세게 불어요' 포함)
		record(session, newKey(), "VERY_HOT", "VERY_HUMID", "STRONG", INSIDE_N1).andExpect(status().isOk())
			.andExpect(jsonPath("$.windTag").value("STRONG"));
	}

	/** BR-FEL-02: 쓰기 직전 현재 동네 확정이 거부되면 기록하지 않는다. */
	@Test
	void recordIsRejectedWhenCurrentNeighborhoodCannotBeConfirmed() throws Exception {
		clock.set(NOW);
		AuthTestClient.Session session = signup(TemperatureSensitivity.NORMAL);
		record(session, newKey(), "COLD", null, null, AT_SEA).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("CURRENT_NEIGHBORHOOD_UNVERIFIED"))
			.andExpect(jsonPath("$.reason").value("OUTSIDE_SERVICE_AREA"));
		record(session, newKey(), "COLD", null, null, NeighborhoodTestData.location(37.506, 127.004, 900.0))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.reason").value("ACCURACY_TOO_LOW"));
		mvc.perform(post("/api/v1/feeling/records").header(HttpHeaders.AUTHORIZATION, session.bearer())
			.header(IdempotencyKey.HEADER, newKey())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"temperature\":\"COLD\"}"))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.reason").value("LOCATION_MISSING"));
		assertThat(recordCount()).isZero();
	}

	/** FR-FEL-01: 확정된 동네에 기록하고, 기록 시점 민감도·관측 스냅샷(값·관측 시각·항목별 출처)을 함께 저장한다. 좌표는 남기지 않는다. */
	@Test
	void directRecordStoresSensitivityAndObservationSnapshot() throws Exception {
		clock.set(NOW);
		stubN1Observation(18.0);
		AuthTestClient.Session session = signup(TemperatureSensitivity.COLD_SENSITIVE);

		String id = recordId(record(session, newKey(), "SLIGHTLY_COLD", "DRY", null, INSIDE_N1)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.neighborhoodId").value(N1.value()))
			.andExpect(jsonPath("$.temperatureTag").value("SLIGHTLY_COLD"))
			.andExpect(jsonPath("$.humidityTag").value("DRY"))
			.andExpect(jsonPath("$.windTag").doesNotExist())
			.andExpect(jsonPath("$.temperatureSensitivity").value("COLD_SENSITIVE"))
			.andExpect(jsonPath("$.source").value("DIRECT"))
			.andExpect(jsonPath("$.snapshotSecured").value(true))
			.andExpect(jsonPath("$.overwritten").value(false)));

		Map<String, Object> row = jdbc.sql("SELECT * FROM feeling_record WHERE id = :id")
			.param("id", UUID.fromString(id))
			.query()
			.singleRow();
		assertThat(row.get("member_id")).isEqualTo(session.memberId().value());
		assertThat(((Number) row.get("temperature_level")).intValue()).isEqualTo(3);
		assertThat(row.get("snapshot_status")).isEqualTo("SECURED");
		assertThat(row.get("snapshot_temperature")).isEqualTo(18.0);
		assertThat(row.get("snapshot_humidity")).isEqualTo(60.0);
		assertThat(row.get("snapshot_wind_speed")).isEqualTo(2.0);
		assertThat(row.get("snapshot_temperature_source")).isEqualTo("OBSERVATION");
		assertThat(((java.sql.Timestamp) row.get("snapshot_observed_at")).toInstant()).isEqualTo(kst("2026-10-13T09:00"));
		assertThat(row.keySet()).noneMatch(column -> column.contains("latitude") || column.contains("longitude"));

		// 체감기록됨 발행 (contracts §3)
		assertThat(events.stream(FeelingRecorded.class)).anySatisfy(e -> {
			assertThat(e.feelingRecordId().value()).isEqualTo(UUID.fromString(id));
			assertThat(e.memberId()).isEqualTo(session.memberId());
			assertThat(e.neighborhoodId()).isEqualTo(N1);
			assertThat(e.source()).isEqualTo(FeelingRecordSource.DIRECT);
			assertThat(e.change()).isEqualTo(FeelingRecorded.Change.CREATED);
		});
	}

	/** BR-FEL-05: 관측을 얻지 못해도 기록은 저장하고 스냅샷만 '미확보'. */
	@Test
	void recordIsSavedWithMissingSnapshotWhenObservationIsUnavailable() throws Exception {
		clock.set(NOW);
		AuthTestClient.Session session = signup(TemperatureSensitivity.NORMAL);
		String id = recordId(record(session, "COLD").andExpect(status().isOk())
			.andExpect(jsonPath("$.snapshotSecured").value(false)));
		assertThat(jdbc.sql("SELECT snapshot_status FROM feeling_record WHERE id = :id")
			.param("id", UUID.fromString(id))
			.query(String.class)
			.single()).isEqualTo("MISSING");
	}

	/** contracts §4: 같은 요청 식별자의 재요청은 첫 결과를 돌려주고 기록을 더 만들거나 바꾸지 않는다. */
	@Test
	void sameIdempotencyKeyReturnsFirstResult() throws Exception {
		clock.set(NOW);
		AuthTestClient.Session session = signup(TemperatureSensitivity.NORMAL);
		String key = newKey();
		String first = record(session, key, "COLD", null, null, INSIDE_N1).andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		clock.advance(Duration.ofMinutes(5));
		String second = record(session, key, "HOT", null, null, INSIDE_N1).andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(second).isEqualTo(first);
		assertThat(recordCount()).isEqualTo(1);
		assertThat(jdbc.sql("SELECT temperature_level FROM feeling_record").query(Integer.class).single()).isEqualTo(2);
	}

	/**
	 * BR-FEL-04: 같은 회원·동네에서 생성 시각이 feeling.record-interval(1시간) 안이면 덮어쓴다. 간격은 생성 시각 기준이라
	 * 덮어쓰기를 반복해도 늘어나지 않는다. 다른 동네는 별개다.
	 */
	@Test
	void overwriteIntervalIsMeasuredFromCreationTimeNotLastOverwrite() throws Exception {
		clock.set(NOW);
		AuthTestClient.Session session = signup(TemperatureSensitivity.NORMAL);
		String first = recordId(record(session, "COLD").andExpect(jsonPath("$.overwritten").value(false))
			.andExpect(jsonPath("$.overwritableUntil").value("2026-10-13T01:15:00Z")));

		clock.set(kst("2026-10-13T09:45"));
		assertThat(recordId(record(session, "SLIGHTLY_COLD").andExpect(jsonPath("$.overwritten").value(true))
			.andExpect(jsonPath("$.createdAt").value("2026-10-13T00:15:00Z"))
			.andExpect(jsonPath("$.recordedAt").value("2026-10-13T00:45:00Z")))).isEqualTo(first);

		// 마지막 덮어쓰기(09:45)로부터는 1시간 안이지만, 생성(09:15)으로부터 59분이 지났다 → 아직 덮어쓰기
		clock.set(kst("2026-10-13T10:14"));
		assertThat(recordId(record(session, "JUST_RIGHT").andExpect(jsonPath("$.overwritten").value(true))))
			.isEqualTo(first);
		assertThat(recordCount()).isEqualTo(1);

		// 생성 후 정확히 1시간 → 새 기록 (덮어쓰기가 간격을 연장했다면 여기서도 덮어썼을 것)
		clock.set(kst("2026-10-13T10:15"));
		session = relogin(session);
		String second = recordId(record(session, "HOT").andExpect(jsonPath("$.overwritten").value(false)));
		assertThat(second).isNotEqualTo(first);
		assertThat(recordCount()).isEqualTo(2);

		// 다른 동네는 별개 기록
		String other = recordId(record(session, newKey(), "HOT", null, null, NEAR_N2)
			.andExpect(jsonPath("$.neighborhoodId").value(N2.value()))
			.andExpect(jsonPath("$.overwritten").value(false)));
		assertThat(other).isNotEqualTo(second);
		assertThat(recordCount()).isEqualTo(3);
	}

	/** 불변식 3: 동시에 들어온 기록 요청에도 같은 회원·동네·간격 안에는 기록이 하나만 남는다. */
	@Test
	void concurrentRecordsLeaveOneRecord() throws Exception {
		clock.set(NOW);
		AuthTestClient.Session session = signup(TemperatureSensitivity.NORMAL);
		int threads = 6;
		CountDownLatch start = new CountDownLatch(1);
		List<Future<?>> futures = new ArrayList<>();
		try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
			for (int i = 0; i < threads; i++) {
				TemperatureTag tag = TemperatureTag.of(i + 1);
				futures.add(pool.submit(() -> {
					start.await();
					return recordService.record(session.memberId(),
							new FeelingRecordService.RecordCommand(tag, null, null, INSIDE_N1));
				}));
			}
			start.countDown();
			for (Future<?> f : futures) {
				f.get();
			}
		}
		assertThat(recordCount()).isEqualTo(1);
	}

	/**
	 * BR-FEL-04 예외·BR-FEL-06: 피드백은 보여준 온도 태그 기준 한 단계 변환(양 끝 유지)이고 응답 원문·보여준 값을 함께 남긴다.
	 * 직접 기록을 덮어쓸 때는 온도만 바꾸고 습도·바람은 유지(출처 '직접+피드백 보정'). 직접 기록이 피드백 기록을 덮어쓰면 통째로 바뀐다.
	 */
	@Test
	void feedbackConvertsShownTagAndUpdatesOnlyTemperatureOfDirectRecord() throws Exception {
		clock.set(NOW);
		AuthTestClient.Session session = signup(TemperatureSensitivity.NORMAL);
		Instant basis = kst("2026-10-13T09:00");
		Instant derivedAt = kst("2026-10-13T09:10");
		String id = recordId(record(session, newKey(), "COLD", "DRY", "LIGHT", INSIDE_N1));

		clock.set(kst("2026-10-13T09:20"));
		feedback(session, "HOTTER", "SLIGHTLY_COLD", TemperatureSensitivity.NORMAL, N1, basis, derivedAt, INSIDE_N1)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.recordId").value(id))
			.andExpect(jsonPath("$.temperatureTag").value("JUST_RIGHT"))
			.andExpect(jsonPath("$.humidityTag").value("DRY"))
			.andExpect(jsonPath("$.windTag").value("LIGHT"))
			.andExpect(jsonPath("$.source").value("DIRECT_WITH_FEEDBACK"))
			.andExpect(jsonPath("$.overwritten").value(true));
		assertFeedbackInfo(id, "HOTTER", 3);

		// 양 끝 유지: 1단계에서 "더 추워요"는 1단계, 응답 원문으로 더 극단적임을 구분
		feedback(session, "COLDER", "VERY_COLD", TemperatureSensitivity.NORMAL, N1, basis, derivedAt, INSIDE_N1)
			.andExpect(jsonPath("$.temperatureTag").value("VERY_COLD"))
			.andExpect(jsonPath("$.humidityTag").value("DRY"))
			.andExpect(jsonPath("$.source").value("DIRECT_WITH_FEEDBACK"));
		assertFeedbackInfo(id, "COLDER", 1);

		// 직접 기록이 덮어쓰면 통째로(습도·바람·피드백 정보 모두 새 값)
		record(session, newKey(), "HOT", null, null, INSIDE_N1).andExpect(jsonPath("$.recordId").value(id))
			.andExpect(jsonPath("$.source").value("DIRECT"))
			.andExpect(jsonPath("$.humidityTag").doesNotExist())
			.andExpect(jsonPath("$.windTag").doesNotExist());
		assertFeedbackInfo(id, null, null);

		// 피드백만으로 새 기록: 습도·바람 없음, 7단계에서 "더 더워요"는 7단계
		AuthTestClient.Session other = signup(TemperatureSensitivity.HEAT_SENSITIVE);
		String feedbackId = recordId(feedback(other, "HOTTER", "VERY_HOT", TemperatureSensitivity.HEAT_SENSITIVE, N1,
				basis, derivedAt, INSIDE_N1)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.temperatureTag").value("VERY_HOT"))
			.andExpect(jsonPath("$.humidityTag").doesNotExist())
			.andExpect(jsonPath("$.source").value("FEEDBACK"))
			.andExpect(jsonPath("$.overwritten").value(false)));
		// 피드백 기록을 다시 피드백이 덮어쓰면 출처는 피드백 그대로
		feedback(other, "RIGHT", "HOT", TemperatureSensitivity.HEAT_SENSITIVE, N1, basis, derivedAt, INSIDE_N1)
			.andExpect(jsonPath("$.recordId").value(feedbackId))
			.andExpect(jsonPath("$.temperatureTag").value("HOT"))
			.andExpect(jsonPath("$.source").value("FEEDBACK"));
		assertFeedbackInfo(feedbackId, "RIGHT", 6);
	}

	/**
	 * BR-FEL-07: 피드백 대상은 현재 동네 · 회원의 현재 민감도 · 현재 시각으로 산출해 보여준 값뿐이고,
	 * 산출 후 feeling.feedback.max-shown-age(1시간) 안이어야 한다.
	 */
	@Test
	void feedbackOnNonTargetValuesIsRejected() throws Exception {
		clock.set(NOW);
		AuthTestClient.Session session = signup(TemperatureSensitivity.NORMAL);
		Instant basis = kst("2026-10-13T09:00");
		Instant derivedAt = kst("2026-10-13T09:10");

		feedback(session, "RIGHT", "JUST_RIGHT", TemperatureSensitivity.NORMAL, N2, basis, derivedAt, INSIDE_N1)
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("FEEDBACK_TARGET_INVALID"))
			.andExpect(jsonPath("$.reason").value("NEIGHBORHOOD_MISMATCH"));
		feedback(session, "RIGHT", "JUST_RIGHT", TemperatureSensitivity.COLD_SENSITIVE, N1, basis, derivedAt, INSIDE_N1)
			.andExpect(jsonPath("$.reason").value("SENSITIVITY_MISMATCH"));
		// 외출 시각(미래) 값
		feedback(session, "RIGHT", "JUST_RIGHT", TemperatureSensitivity.NORMAL, N1, kst("2026-10-13T18:00"), derivedAt,
				INSIDE_N1)
			.andExpect(jsonPath("$.reason").value("NOT_CURRENT_TIME"));
		// 어제 값
		feedback(session, "RIGHT", "JUST_RIGHT", TemperatureSensitivity.NORMAL, N1, kst("2026-10-12T09:00"), derivedAt,
				INSIDE_N1)
			.andExpect(jsonPath("$.reason").value("NOT_CURRENT_TIME"));
		// 산출 후 1시간 넘게 지난 값
		feedback(session, "RIGHT", "JUST_RIGHT", TemperatureSensitivity.NORMAL, N1, kst("2026-10-13T08:00"),
				kst("2026-10-13T08:14"), INSIDE_N1)
			.andExpect(jsonPath("$.reason").value("STALE"));
		// 현재 동네 확인 실패도 거부
		feedback(session, "RIGHT", "JUST_RIGHT", TemperatureSensitivity.NORMAL, N1, basis, derivedAt, AT_SEA)
			.andExpect(jsonPath("$.code").value("CURRENT_NEIGHBORHOOD_UNVERIFIED"));
		assertThat(recordCount()).isZero();

		// 정시를 넘겨도 산출 후 1시간 안이면 받는다(09:10에 본 09시 값에 10:05 응답)
		clock.set(kst("2026-10-13T10:05"));
		feedback(session, "RIGHT", "JUST_RIGHT", TemperatureSensitivity.NORMAL, N1, basis, derivedAt, INSIDE_N1)
			.andExpect(status().isOk());
	}

	/** FR-FEL-10: 최근 기록(피드백 버튼 상태), 소유 확인(동네·시각 포함), 일괄 조회(회원 ID 없음, 없는 ID는 빠짐). */
	@Test
	void recentRecordOwnershipAndBatchLookup() throws Exception {
		clock.set(NOW);
		AuthTestClient.Session session = signup(TemperatureSensitivity.HEAT_SENSITIVE);
		AuthTestClient.Session stranger = signup(TemperatureSensitivity.NORMAL);
		assertThat(feelingApi.findRecentRecord(session.memberId(), N1)).isEmpty();
		FeelingRecordId id = new FeelingRecordId(UUID.fromString(recordId(record(session, newKey(), "HOT", "HUMID",
				null, INSIDE_N1))));

		clock.set(kst("2026-10-13T09:40"));
		RecentFeelingRecord recent = feelingApi.findRecentRecord(session.memberId(), N1).orElseThrow();
		assertThat(recent.id()).isEqualTo(id);
		assertThat(recent.source()).isEqualTo(FeelingRecordSource.DIRECT);
		assertThat(recent.temperatureTag()).isEqualTo(TemperatureTag.HOT);
		assertThat(recent.overwritableUntil()).isEqualTo(kst("2026-10-13T10:15"));
		assertThat(feelingApi.findRecentRecord(session.memberId(), N2)).isEmpty();
		clock.set(kst("2026-10-13T10:15"));
		assertThat(feelingApi.findRecentRecord(session.memberId(), N1)).isEmpty();

		FeelingRecordOwnership owned = feelingApi.checkOwnership(session.memberId(), id);
		assertThat(owned.owned()).isTrue();
		assertThat(owned.neighborhoodId()).isEqualTo(N1);
		assertThat(owned.recordedAt()).isEqualTo(NOW);
		assertThat(feelingApi.checkOwnership(stranger.memberId(), id).owned()).isFalse();
		assertThat(feelingApi.checkOwnership(session.memberId(), new FeelingRecordId(UUID.randomUUID())).owned())
			.isFalse();

		FeelingRecordId missing = new FeelingRecordId(UUID.randomUUID());
		Map<FeelingRecordId, FeelingRecordView> views = feelingApi.findRecords(List.of(id, missing));
		assertThat(views).containsOnlyKeys(id);
		FeelingRecordView view = views.get(id);
		assertThat(view.temperatureTag()).isEqualTo(TemperatureTag.HOT);
		assertThat(view.humidityTag()).isEqualTo(HumidityTag.HUMID);
		assertThat(view.neighborhoodId()).isEqualTo(N1);
		assertThat(view.temperatureSensitivity()).isEqualTo(TemperatureSensitivity.HEAT_SENSITIVE);
	}

	private void assertFeedbackInfo(String id, String answer, Integer shownLevel) {
		Map<String, Object> row = jdbc.sql("SELECT feedback_answer, feedback_shown_level, feedback_shown_source "
				+ "FROM feeling_record WHERE id = :id").param("id", UUID.fromString(id)).query().singleRow();
		assertThat(row.get("feedback_answer")).isEqualTo(answer);
		assertThat(row.get("feedback_shown_level") == null ? null : ((Number) row.get("feedback_shown_level")).intValue())
			.isEqualTo(shownLevel);
		assertThat(row.get("feedback_shown_source")).isEqualTo(answer == null ? null : "REFERENCE_TABLE");
	}

}
