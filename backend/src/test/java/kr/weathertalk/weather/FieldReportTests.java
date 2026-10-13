package kr.weathertalk.weather;

import static kr.weathertalk.neighborhood.NeighborhoodTestData.AT_SEA;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.INSIDE_N1;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N1;
import static kr.weathertalk.weather.WeatherStubs.kst;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import com.jayway.jsonpath.JsonPath;

import kr.weathertalk.AuthTestClient;
import kr.weathertalk.IntegrationTest;
import kr.weathertalk.shared.Ids.MemberId;
import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;

/**
 * 현장 제보 하기·취소·현황 (FR-WEA-04·05·14, BR-WEA-15·16·16a).
 */
@IntegrationTest
class FieldReportTests extends FieldReportTestBase {

	static final Instant NOW = kst("2026-10-13T10:00");

	/** BR-WEA-15: 비회원은 가입 필요. contracts §4: 약관 재동의 전에는 제보할 수 없지만 본인 제보 취소와 현황 조회는 할 수 있다. */
	@Test
	void guestCannotReportAndTermsReagreementBlocksReportButNotCancel() throws Exception {
		clock.set(NOW);
		mvc.perform(post("/api/v1/weather/field-reports").header("Idempotency-Key", newKey())
			.contentType("application/json")
			.content("{\"kind\":\"RAIN\",\"latitude\":37.506,\"longitude\":127.004,\"accuracyMeters\":20}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("SIGNUP_REQUIRED"));

		AuthTestClient.Session session = new AuthTestClient(mvc).signup();
		String reportId = JsonPath.read(submitViaApi(session, newKey(), "RAIN", INSIDE_N1).andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString(), "$.report.reportId");

		jdbc.sql("INSERT INTO member_terms (kind, version, title, required, major_revision, effective_at, content_url) "
				+ "VALUES ('SERVICE', 97, 'v97', true, true, '2026-01-02 00:00:00+09', 'about:blank')").update();
		try {
			submitViaApi(session, newKey(), "CLEAR", INSIDE_N1).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("TERMS_REAGREEMENT_REQUIRED"));
			mvc.perform(get("/api/v1/weather/neighborhoods/" + N1.value() + "/field-reports")
				.header(HttpHeaders.AUTHORIZATION, session.bearer())).andExpect(status().isOk());
			mvc.perform(post("/api/v1/weather/field-reports/" + reportId + "/cancel")
				.header(HttpHeaders.AUTHORIZATION, session.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.report.canceledAt").exists());
		}
		finally {
			jdbc.sql("DELETE FROM member_terms WHERE version = 97").update();
		}
		assertThat(reportRows()).isEqualTo(1);
	}

	/** FR-WEA-04 실패 케이스: 알 수 없는 종류는 검증 오류, 현재 동네 확정이 거부되면 저장하지 않는다. 동네 ID는 받지 않는다. */
	@Test
	void unknownKindAndUnconfirmedNeighborhoodAreRejected() throws Exception {
		clock.set(NOW);
		AuthTestClient.Session session = new AuthTestClient(mvc).signup();
		submitViaApi(session, newKey(), "FOG", INSIDE_N1).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		submitViaApi(session, newKey(), null, INSIDE_N1).andExpect(status().isBadRequest());
		submitViaApi(session, newKey(), "RAIN", AT_SEA).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("CURRENT_NEIGHBORHOOD_UNVERIFIED"));
		assertThat(reportRows()).isZero();
	}

	/** FR-WEA-04: 확정된 현재 동네에 저장하고 갱신된 현황을 돌려준다. 같은 요청 식별자의 반복은 처음 결과(contracts §4). */
	@Test
	void reportIsStoredInConfirmedNeighborhoodAndIdempotent() throws Exception {
		clock.set(NOW);
		AuthTestClient.Session session = new AuthTestClient(mvc).signup();
		String key = newKey();
		String first = submitViaApi(session, key, "RAIN", INSIDE_N1).andExpect(status().isOk())
			.andExpect(jsonPath("$.report.neighborhoodId.value").value(N1.value()))
			.andExpect(jsonPath("$.report.axis").value("SKY_PRECIPITATION"))
			.andExpect(jsonPath("$.report.kind").value("RAIN"))
			.andExpect(jsonPath("$.report.cancelableUntil").value("2026-10-13T01:10:00Z"))
			.andExpect(jsonPath("$.status.axes[0].reporterCount").value(1))
			.andExpect(jsonPath("$.status.myReports[0].kind").value("RAIN"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		String again = submitViaApi(session, key, "RAIN", INSIDE_N1).andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat((String) JsonPath.read(again, "$.report.reportId"))
			.isEqualTo(JsonPath.read(first, "$.report.reportId"));
		assertThat(reportRows()).isEqualTo(1);
		// 좌표는 저장하지 않는다(ADR 0009)
		assertThat(jdbc.sql("SELECT count(*) FROM information_schema.columns WHERE table_name = 'weather_field_report' "
				+ "AND (column_name LIKE '%lat%' OR column_name LIKE '%lon%')").query(Integer.class).single()).isZero();
	}

	/** BR-WEA-16: 같은 동네·같은 축 min-interval(30분) 이내(경계 포함) 재제보는 대체, 넘으면 새 제보. 다른 축은 따로 산다. */
	@Test
	void reportWithinMinIntervalReplacesPreviousOne() {
		clock.set(NOW);
		MemberId member = newMember();
		FieldReport rain = report(member, FieldReportKind.RAIN).report();
		report(member, FieldReportKind.DUST_BAD);

		clock.set(NOW.plus(Duration.ofMinutes(30)));
		FieldReportStatus status = report(member, FieldReportKind.CLEAR).status();
		assertThat(status.axis(FieldReportAxis.SKY_PRECIPITATION).orElseThrow().reporterCount()).isEqualTo(1);
		assertThat(status.axis(FieldReportAxis.SKY_PRECIPITATION).orElseThrow().counts())
			.containsExactly(new FieldReportStatus.KindCount(FieldReportKind.CLEAR, 1));
		assertThat(status.axis(FieldReportAxis.AIR).orElseThrow().reporterCount()).isEqualTo(1);
		assertThat(replacedAt(rain.reportId())).isNotNull();

		clock.set(NOW.plus(Duration.ofMinutes(61)));
		FieldReport overcast = report(member, FieldReportKind.OVERCAST).report();
		assertThat(jdbc.sql("SELECT count(*) FROM weather_field_report WHERE replaced_at IS NULL AND axis = 'SKY_PRECIPITATION'")
			.query(Integer.class)
			.single()).isEqualTo(2); // 31분 전 제보(맑음)는 대체하지 않는다
		FieldReportStatus latest = fieldReports.status(N1, member);
		// 집계는 회원별·축별 최신 1건 — 한 회원은 1명으로 센다
		assertThat(latest.axis(FieldReportAxis.SKY_PRECIPITATION).orElseThrow().counts())
			.containsExactly(new FieldReportStatus.KindCount(FieldReportKind.OVERCAST, 1));
		assertThat(latest.myReports()).extracting(FieldReport::reportId).contains(overcast.reportId());
	}

	/** BR-WEA-16·FR-WEA-14 규칙 6: 하루 daily-limit(20)건까지, 21번째는 거부. 대체·취소된 제보도 센다. 날짜는 Asia/Seoul로 바뀐다. */
	@Test
	void dailyLimitCountsReplacedAndCanceledReportsAndResetsAtSeoulMidnight() {
		clock.set(kst("2026-10-13T23:00"));
		MemberId member = newMember();
		FieldReport first = report(member, FieldReportKind.RAIN).report();
		fieldReports.cancel(member, first.reportId());
		for (int i = 1; i < 20; i++) {
			report(member, i % 2 == 0 ? FieldReportKind.CLEAR : FieldReportKind.OVERCAST);
		}
		assertThatThrownBy(() -> report(member, FieldReportKind.DUST_BAD)).isInstanceOf(DomainException.class)
			.extracting(e -> ((DomainException) e).code())
			.isEqualTo(ErrorCode.FIELD_REPORT_DAILY_LIMIT_EXCEEDED);
		assertThat(reportRows()).isEqualTo(20);

		clock.set(kst("2026-10-14T00:00"));
		report(member, FieldReportKind.DUST_BAD);
		assertThat(reportRows()).isEqualTo(21);
	}

	/** FR-WEA-14: 본인만, cancel-window(10분) 이내(경계 포함). 이미 취소된 제보는 성공(멱등). 남의 제보는 '없음'과 같은 응답. */
	@Test
	void cancelWindowBoundaryOwnershipAndIdempotency() {
		clock.set(NOW);
		MemberId member = newMember();
		MemberId other = newMember();
		FieldReport onTime = report(member, FieldReportKind.RAIN).report();
		FieldReport late = report(member, FieldReportKind.AIR_CLEAN).report();

		assertThatThrownBy(() -> fieldReports.cancel(other, onTime.reportId())).isInstanceOf(DomainException.class)
			.extracting(e -> ((DomainException) e).code())
			.isEqualTo(ErrorCode.NOT_FOUND);
		assertThatThrownBy(() -> fieldReports.cancel(member, UUID.randomUUID())).isInstanceOf(DomainException.class)
			.extracting(e -> ((DomainException) e).code())
			.isEqualTo(ErrorCode.NOT_FOUND);

		clock.set(NOW.plus(Duration.ofMinutes(10)));
		FieldReportResult canceled = fieldReports.cancel(member, onTime.reportId());
		assertThat(canceled.report().canceledAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
		// 취소된 제보는 집계·내 제보에서 빠진다
		assertThat(canceled.status().axis(FieldReportAxis.SKY_PRECIPITATION).orElseThrow().reporterCount()).isZero();
		assertThat(canceled.status().myReports()).extracting(FieldReport::kind).containsExactly(FieldReportKind.AIR_CLEAN);

		clock.set(NOW.plus(Duration.ofMinutes(10)).plusSeconds(1));
		assertThatThrownBy(() -> fieldReports.cancel(member, late.reportId())).isInstanceOf(DomainException.class)
			.extracting(e -> ((DomainException) e).code())
			.isEqualTo(ErrorCode.FIELD_REPORT_CANCEL_WINDOW_PASSED);
		// 이미 취소된 제보는 기한이 지나도 성공으로 응답
		assertThat(fieldReports.cancel(member, onTime.reportId()).report().canceledAt())
			.isEqualTo(NOW.plus(Duration.ofMinutes(10)));
	}

	/** FR-WEA-05: valid-window(60분) 이내(경계 포함) 제보만 센다. 비회원에게도 공개하되 제보자 정보는 주지 않는다. */
	@Test
	void statusCountsOnlyReportsWithinValidWindowAndHidesReporters() throws Exception {
		clock.set(NOW);
		MemberId member = newMember();
		report(member, FieldReportKind.RAIN);
		report(newMember(), FieldReportKind.RAIN);
		report(newMember(), FieldReportKind.CLEAR);

		clock.set(NOW.plus(Duration.ofMinutes(60)));
		FieldReportStatus status = fieldReports.status(N1, member);
		FieldReportStatus.AxisStatus sky = status.axis(FieldReportAxis.SKY_PRECIPITATION).orElseThrow();
		assertThat(sky.reporterCount()).isEqualTo(3);
		assertThat(sky.counts()).containsExactly(new FieldReportStatus.KindCount(FieldReportKind.RAIN, 2),
				new FieldReportStatus.KindCount(FieldReportKind.CLEAR, 1));
		assertThat(sky.majorityKind()).isEqualTo(FieldReportKind.RAIN); // 3명 중 2명(67%) ≥ 60%
		assertThat(sky.latestReportedAt()).isEqualTo(NOW);
		assertThat(status.myReports()).hasSize(1);
		assertThat(status.axis(FieldReportAxis.AIR).orElseThrow().reporterCount()).isZero();

		mvc.perform(get("/api/v1/weather/neighborhoods/" + N1.value() + "/field-reports"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.axes[0].reporterCount").value(3))
			.andExpect(jsonPath("$.myReports").isEmpty())
			.andExpect(jsonPath("$..memberId").isEmpty());

		clock.set(NOW.plus(Duration.ofMinutes(60)).plusSeconds(1));
		assertThat(fieldReports.status(N1, member).isEmpty()).isTrue();
	}

	private java.sql.Timestamp replacedAt(UUID id) {
		return jdbc.sql("SELECT replaced_at FROM weather_field_report WHERE id = :id")
			.param("id", id)
			.query((rs, n) -> rs.getTimestamp(1))
			.single();
	}

}
