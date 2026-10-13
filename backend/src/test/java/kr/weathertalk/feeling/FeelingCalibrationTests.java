package kr.weathertalk.feeling;

import static kr.weathertalk.neighborhood.NeighborhoodTestData.N1;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N2;
import static kr.weathertalk.weather.WeatherStubs.kst;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.web.servlet.ResultActions;

import kr.weathertalk.AuthTestClient;
import kr.weathertalk.IntegrationTest;
import kr.weathertalk.feeling.internal.FeelingCalibrationService;
import kr.weathertalk.feeling.internal.FeelingMappingRecalculation;
import kr.weathertalk.member.MemberApi;
import kr.weathertalk.member.SensitivityChangeSource;
import kr.weathertalk.member.TemperatureSensitivity;
import kr.weathertalk.member.TemperatureSensitivityChanged;

/**
 * 민감도 보정 제안 (FR-FEL-07·08, BR-FEL-17): 판정 경계(최소 기록·margin·cooldown·decline-cooldown·매핑 없음), 조회(마이페이지·홈),
 * 수락(member 온도 민감도 변경, 출처 = 보정 제안 수락)·거절, 다른 경로의 민감도 변경 시 무효화, 만료, 탈퇴 시 삭제.
 * <p>
 * 회원(보통)의 기록은 13.5℃(기준표: 더위 3 · 보통 3 · 추위 2)에서 남긴다. 매핑은 익명 20℃ 표본으로 만들어(13.5℃ 셀 없음) 예상 단계는
 * 기준표로 내려간다. 2단계 기록만 있으면 보통 오차 1, 추위를 많이 탐 오차 0 → '추위를 많이 탐'을 제안한다.
 */
@IntegrationTest
class FeelingCalibrationTests extends FeelingTestBase {

	static final Instant NOW = kst("2026-10-13T09:15");

	@Autowired
	FeelingMappingRecalculation recalculation;

	@Autowired
	FeelingCalibrationService calibration;

	@Autowired
	MemberApi memberApi;

	@Autowired
	ApplicationEvents events;

	private void activeMapping() {
		for (int i = 0; i < 30; i++) {
			insertRecord(N2, TemperatureSensitivity.NORMAL, NOW.minus(Duration.ofDays(2)).minusSeconds(i), 4, null, null,
					FeelingRecordSource.DIRECT, null, 20.0);
		}
		assertThat(recalculation.recalculate().status()).isEqualTo("APPLIED");
		mappingFlag.set(true);
	}

	/** 회원의 확보 기록 n건(13.5℃, 기록 시각은 at부터 1분씩 앞으로). */
	private void memberRecords(AuthTestClient.Session session, int n, int level, Instant at,
			FeelingRecordSource source) {
		for (int i = 0; i < n; i++) {
			insertRecord(N2, TemperatureSensitivity.NORMAL, at.minus(Duration.ofMinutes(i + 1)), level, null, null,
					source, session.memberId().value(), 13.5);
		}
	}

	private void memberRecords(AuthTestClient.Session session, int n, int level, Instant at) {
		memberRecords(session, n, level, at, FeelingRecordSource.DIRECT);
	}

	private Optional<CalibrationOffer> offer(AuthTestClient.Session session) {
		return feelingApi.findCalibrationOffer(session.memberId());
	}

	private String offerStatus(UUID offerId) {
		return jdbc.sql("SELECT status FROM feeling_calibration_offer WHERE id = :id")
			.param("id", offerId)
			.query(String.class)
			.single();
	}

	private ResultActions respond(AuthTestClient.Session session, UUID offerId, String action) throws Exception {
		return mvc.perform(post("/api/v1/members/me/calibration-offers/{id}/" + action, offerId)
			.header(HttpHeaders.AUTHORIZATION, session.bearer()));
	}

	private CalibrationOffer offered(AuthTestClient.Session session) {
		memberRecords(session, 20, 2, NOW);
		assertThat(calibration.judgeAll().offered()).isEqualTo(1);
		return offer(session).orElseThrow();
	}

	/** 규칙 2: 가중 합이 min-records(20) 미만이면 판정하지 않고(쿨다운도 시작 안 함), 20건이면 제안. 제안은 마이페이지·홈에서 조회한다. */
	@Test
	void offerFromMinRecordsIsShownOnMyPageAndHome() throws Exception {
		clock.set(NOW);
		activeMapping();
		AuthTestClient.Session session = signup(TemperatureSensitivity.NORMAL);
		memberRecords(session, 19, 2, NOW);
		// 피드백 기록은 0.5로 센다: 19 + 1×0.5 = 19.5 < 20
		memberRecords(session, 1, 2, NOW.minus(Duration.ofHours(1)), FeelingRecordSource.FEEDBACK);

		FeelingCalibrationService.JudgeResult skipped = calibration.judgeAll();
		assertThat(skipped.judged()).isZero();
		assertThat(offer(session)).isEmpty();

		memberRecords(session, 1, 2, NOW.minus(Duration.ofHours(2)));
		FeelingCalibrationService.JudgeResult result = calibration.judgeAll();
		assertThat(result.judged()).isEqualTo(1);
		assertThat(result.offered()).isEqualTo(1);
		CalibrationOffer offer = offer(session).orElseThrow();
		assertThat(offer.currentGroup()).isEqualTo(TemperatureSensitivity.NORMAL);
		assertThat(offer.suggestedGroup()).isEqualTo(TemperatureSensitivity.COLD_SENSITIVE);
		assertThat(offer.recordCount()).isEqualTo(21);
		assertThat(offer.weightedRecordCount()).isEqualTo(20.5);
		assertThat(offer.errors().get(TemperatureSensitivity.COLD_SENSITIVE)).isZero();
		assertThat(offer.errors().get(TemperatureSensitivity.NORMAL)).isEqualTo(1.0);
		assertThat(offer.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(14)));

		mvc.perform(get("/api/v1/members/me/calibration-offer").header(HttpHeaders.AUTHORIZATION, session.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.offer.offerId").value(offer.offerId().toString()))
			.andExpect(jsonPath("$.offer.suggestedGroup").value("COLD_SENSITIVE"))
			.andExpect(jsonPath("$.offer.status").value("OFFERED"));
		mvc.perform(get("/api/v1/home").param("neighborhoodId", N1.value())
			.header(HttpHeaders.AUTHORIZATION, session.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.calibrationOffer.status").value("OK"))
			.andExpect(jsonPath("$.calibrationOffer.data.suggestedGroup").value("COLD_SENSITIVE"));
		// 비회원 홈에는 섹션이 없다
		mvc.perform(get("/api/v1/home").param("neighborhoodId", N1.value()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.calibrationOffer").doesNotExist());
		// 이미 '제안됨'이 있으면 새로 만들지 않는다
		assertThat(calibration.judgeAll().offered()).isZero();
	}

	/** 규칙 7: 쓸 수 있는 매핑(플래그 + 적용 중 버전)이 없으면 판정하지 않는다 — 기준표만으로 그룹을 바꾸라고 권하지 않는다. */
	@Test
	void noJudgementWithoutActiveMapping() throws Exception {
		clock.set(NOW);
		AuthTestClient.Session session = signup(TemperatureSensitivity.NORMAL);
		memberRecords(session, 20, 2, NOW); // 매핑 최소 표본(30) 미만이라 이 기록으로 셀이 생기지 않는다
		assertThat(calibration.judgeAll().judged()).isZero(); // 매핑 버전 없음

		activeMapping();
		mappingFlag.set(false);
		assertThat(calibration.judgeAll().judged()).isZero(); // 적용 중 버전은 있으나 플래그 꺼짐
		assertThat(offer(session)).isEmpty();

		mappingFlag.set(true);
		assertThat(calibration.judgeAll().offered()).isEqualTo(1);
	}

	/** 규칙 6: 판정 후 cooldown(30일) 동안은 다시 판정하지 않는다(경계값: 30일이 되는 순간부터 판정). */
	@Test
	void judgementCooldown() throws Exception {
		clock.set(NOW);
		activeMapping();
		AuthTestClient.Session session = signup(TemperatureSensitivity.NORMAL);
		memberRecords(session, 20, 3, NOW); // 보통이 가장 잘 맞음 → 판정했으나 제안 없음
		FeelingCalibrationService.JudgeResult first = calibration.judgeAll();
		assertThat(first.judged()).isEqualTo(1);
		assertThat(first.offered()).isZero();

		jdbc.sql("DELETE FROM feeling_record WHERE member_id = :m").param("m", session.memberId().value()).update();
		Instant almost = NOW.plus(Duration.ofDays(30)).minusSeconds(1);
		memberRecords(session, 20, 2, almost);
		clock.set(almost);
		assertThat(calibration.judgeAll().judged()).isZero();

		clock.set(NOW.plus(Duration.ofDays(30)));
		assertThat(calibration.judgeAll().offered()).isEqualTo(1);
	}

	/**
	 * 거절(FR-FEL-08 규칙 3): '거절됨'으로 바꾸고 decline-cooldown(90일)을 시작한다. 판정 cooldown(30일)이 지나도 거절 후 90일 전에는 판정하지
	 * 않는다. 거절을 다시 보내도 같은 결과, 거절한 제안은 수락할 수 없다.
	 */
	@Test
	void declineStartsDeclineCooldown() throws Exception {
		clock.set(NOW);
		activeMapping();
		AuthTestClient.Session session = signup(TemperatureSensitivity.NORMAL);
		CalibrationOffer offer = offered(session);

		respond(session, offer.offerId(), "decline").andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("DECLINED"));
		respond(session, offer.offerId(), "decline").andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("DECLINED"));
		respond(session, offer.offerId(), "accept").andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("CALIBRATION_OFFER_NOT_ACTIVE"))
			.andExpect(jsonPath("$.reason").value("DECLINED"));
		assertThat(offer(session)).isEmpty();
		assertThat(memberApi.findProfile(session.memberId()).orElseThrow().temperatureSensitivity())
			.isEqualTo(TemperatureSensitivity.NORMAL);

		Instant afterJudgeCooldown = NOW.plus(Duration.ofDays(31));
		clock.set(afterJudgeCooldown);
		memberRecords(session, 20, 2, afterJudgeCooldown);
		assertThat(calibration.judgeAll().judged()).isZero();

		Instant afterDeclineCooldown = NOW.plus(Duration.ofDays(90));
		clock.set(afterDeclineCooldown);
		memberRecords(session, 20, 2, afterDeclineCooldown);
		assertThat(calibration.judgeAll().offered()).isEqualTo(1);
	}

	/**
	 * 수락(FR-FEL-08 규칙 2): feeling이 member 온도 민감도 변경(출처 = 보정 제안 수락)을 부르고 '수락됨'. 다시 보내도 같은 결과이며 민감도를
	 * 두 번 바꾸지 않는다. 변경 후 cooldown 동안은 다시 판정하지 않는다.
	 */
	@Test
	void acceptChangesSensitivityThroughMember() throws Exception {
		clock.set(NOW);
		activeMapping();
		AuthTestClient.Session session = signup(TemperatureSensitivity.NORMAL);
		CalibrationOffer offer = offered(session);

		respond(session, offer.offerId(), "accept").andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ACCEPTED"));
		respond(session, offer.offerId(), "accept").andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ACCEPTED"));

		assertThat(memberApi.findProfile(session.memberId()).orElseThrow().temperatureSensitivity())
			.isEqualTo(TemperatureSensitivity.COLD_SENSITIVE);
		assertThat(events.stream(TemperatureSensitivityChanged.class)
			.filter(e -> e.memberId().equals(session.memberId())))
			.singleElement()
			.satisfies(e -> {
				assertThat(e.source()).isEqualTo(SensitivityChangeSource.CALIBRATION_ACCEPTED);
				assertThat(e.previous()).isEqualTo(TemperatureSensitivity.NORMAL);
				assertThat(e.current()).isEqualTo(TemperatureSensitivity.COLD_SENSITIVE);
			});
		// 구독이 쿨다운 기준 시각만 갱신하고 수락 상태는 그대로 둔다
		Awaitility.await()
			.atMost(Duration.ofSeconds(10))
			.until(() -> jdbc.sql("SELECT count(*) FROM feeling_calibration_member WHERE member_id = :m "
					+ "AND last_sensitivity_changed_at IS NOT NULL")
				.param("m", session.memberId().value())
				.query(Integer.class)
				.single() == 1);
		assertThat(offerStatus(offer.offerId())).isEqualTo("ACCEPTED");
		assertThat(offer(session)).isEmpty();

		// 남의 제안은 없는 것과 같다
		AuthTestClient.Session other = signup(TemperatureSensitivity.NORMAL);
		respond(other, offer.offerId(), "accept").andExpect(status().isNotFound());
	}

	/** 04 §6.4: 회원이 마이페이지에서 직접 민감도를 바꾸면 '제안됨' 제안은 '무효'가 되고, 이후 수락할 수 없다. */
	@Test
	void directSensitivityChangeInvalidatesOffer() throws Exception {
		clock.set(NOW);
		activeMapping();
		AuthTestClient.Session session = signup(TemperatureSensitivity.NORMAL);
		CalibrationOffer offer = offered(session);

		mvc.perform(put("/api/v1/members/me/temperature-sensitivity").header(HttpHeaders.AUTHORIZATION, session.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"temperatureSensitivity\":\"HEAT_SENSITIVE\"}")).andExpect(status().isOk());

		Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> offerStatus(offer.offerId()).equals("INVALIDATED"));
		assertThat(offer(session)).isEmpty();
		respond(session, offer.offerId(), "accept").andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.reason").value("INVALIDATED"));
		assertThat(memberApi.findProfile(session.memberId()).orElseThrow().temperatureSensitivity())
			.isEqualTo(TemperatureSensitivity.HEAT_SENSITIVE);
	}

	/** 앱이 member API를 출처 '보정 제안 수락'으로 직접 불러 제안 그룹으로 바꿔도 구독이 '수락됨'으로 처리한다. */
	@Test
	void memberChangeWithCalibrationSourceMarksAccepted() throws Exception {
		clock.set(NOW);
		activeMapping();
		AuthTestClient.Session session = signup(TemperatureSensitivity.NORMAL);
		CalibrationOffer offer = offered(session);

		mvc.perform(put("/api/v1/members/me/temperature-sensitivity").header(HttpHeaders.AUTHORIZATION, session.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"temperatureSensitivity\":\"COLD_SENSITIVE\",\"source\":\"CALIBRATION_ACCEPTED\"}"))
			.andExpect(status().isOk());

		Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> offerStatus(offer.offerId()).equals("ACCEPTED"));
	}

	/** 유효 기간(offer-ttl 14일)이 지나면 조회에서 사라지고, 다음 판정 때 '만료됨'이 되며 응답할 수 없다. */
	@Test
	void expiredOfferIsHiddenAndExpired() throws Exception {
		clock.set(NOW);
		activeMapping();
		AuthTestClient.Session session = signup(TemperatureSensitivity.NORMAL);
		CalibrationOffer offer = offered(session);

		clock.set(NOW.plus(Duration.ofDays(14)));
		assertThat(offer(session)).isEmpty();
		assertThat(calibration.judgeAll().expired()).isEqualTo(1);
		assertThat(offerStatus(offer.offerId())).isEqualTo("EXPIRED");
		AuthTestClient.Session fresh = relogin(session);
		respond(fresh, offer.offerId(), "decline").andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.reason").value("EXPIRED"));
	}

	/** 탈퇴(FR-FEL-09): 회원과 연결된 보정 제안 이력과 빈도 제한 기준 시각을 지운다(멱등). */
	@Test
	void withdrawalDeletesCalibrationOffers() throws Exception {
		clock.set(NOW);
		activeMapping();
		AuthTestClient.Session session = signup(TemperatureSensitivity.NORMAL);
		offered(session);

		mvc.perform(delete("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION, session.bearer()))
			.andExpect(status().isNoContent());

		Awaitility.await()
			.atMost(Duration.ofSeconds(10))
			.until(() -> jdbc.sql("SELECT (SELECT count(*) FROM feeling_calibration_offer WHERE member_id = :m) "
					+ "+ (SELECT count(*) FROM feeling_calibration_member WHERE member_id = :m)")
				.param("m", session.memberId().value())
				.query(Integer.class)
				.single() == 0);
	}

}
