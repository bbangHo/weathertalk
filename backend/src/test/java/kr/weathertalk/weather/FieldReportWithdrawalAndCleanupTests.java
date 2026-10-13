package kr.weathertalk.weather;

import static kr.weathertalk.neighborhood.NeighborhoodTestData.INSIDE_N1;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N1;
import static kr.weathertalk.weather.WeatherStubs.kst;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.modulith.test.Scenario;

import kr.weathertalk.AuthTestClient;
import kr.weathertalk.IntegrationTest;
import kr.weathertalk.member.MemberWithdrawn;
import kr.weathertalk.weather.internal.fieldreport.FieldReportCleanupService;

/**
 * 탈퇴 → 회원탈퇴함 → 현장 제보 회원 ID 비우기 (03 §6.4), 현장 제보 정리 (FR-WEA-10).
 */
@IntegrationTest
class FieldReportWithdrawalAndCleanupTests extends FieldReportTestBase {

	@Autowired
	FieldReportCleanupService cleanup;

	@Test
	void withdrawalClearsMemberIdButKeepsReportInAggregation(Scenario scenario) throws Exception {
		clock.set(kst("2026-10-13T10:00"));
		AuthTestClient.Session session = new AuthTestClient(mvc).signup();
		submitViaApi(session, newKey(), "RAIN", INSIDE_N1).andExpect(status().isOk());

		scenario.stimulate(() -> withdraw(session))
			.andWaitAtMost(Duration.ofSeconds(10))
			.andWaitForStateChange(() -> memberReports(session), count -> count == 0)
			.andVerify(count -> {
				// 익명 제보로 남아 현황 집계에 계속 들어간다
				assertThat(fieldReports.status(N1, null).axis(FieldReportAxis.SKY_PRECIPITATION).orElseThrow()
					.reporterCount()).isEqualTo(1);
				assertThat(reportRows()).isEqualTo(1);
			});

		// 같은 이벤트가 다시 와도 결과가 같다(멱등)
		assertThat(jdbc.sql("UPDATE weather_field_report SET member_id = NULL WHERE member_id = :m")
			.param("m", session.memberId().value())
			.update()).isZero();
		org.awaitility.Awaitility.await()
			.atMost(Duration.ofSeconds(10))
			.untilAsserted(() -> assertThat(jdbc.sql("SELECT count(*) FROM event_publication WHERE event_type = :t "
					+ "AND serialized_event LIKE :m AND completion_date IS NULL")
				.param("t", MemberWithdrawn.class.getName())
				.param("m", "%" + session.memberId().value() + "%")
				.query(Integer.class)
				.single()).isZero());
	}

	/** FR-WEA-10: retention(30일)이 지난 제보와 만료된 발행 기록(rain-suppress·episode-expiry 경과)을 지운다. */
	@Test
	void cleanupDeletesReportsAndPublicationRecordsPastRetention() {
		Instant now = kst("2026-11-20T04:00");
		clock.set(now);
		insertReport(now.minus(Duration.ofDays(30)).minusSeconds(1));
		UUID kept = insertReport(now.minus(Duration.ofDays(30)));
		jdbc.sql("INSERT INTO weather_field_report_precip_publication VALUES ('9900000001', 'RAIN', :old, 3), "
				+ "('9900000001', 'SNOW', :recent, 3)")
			.param("old", Timestamp.from(now.minus(Duration.ofHours(3)).minusSeconds(1)))
			.param("recent", Timestamp.from(now.minus(Duration.ofHours(1))))
			.update();
		jdbc.sql("INSERT INTO weather_precip_episode VALUES ('9900000007', :old, :old, :old, NULL), "
				+ "('9900000007', :recent, :recent, :recent, NULL)")
			.param("old", Timestamp.from(now.minus(Duration.ofHours(12))))
			.param("recent", Timestamp.from(now.minus(Duration.ofHours(11))))
			.update();

		cleanup.cleanup();

		assertThat(jdbc.sql("SELECT id FROM weather_field_report").query(UUID.class).list()).containsExactly(kept);
		assertThat(jdbc.sql("SELECT precipitation_type FROM weather_field_report_precip_publication")
			.query(String.class)
			.list()).containsExactly("SNOW");
		assertThat(jdbc.sql("SELECT count(*) FROM weather_precip_episode").query(Integer.class).single()).isEqualTo(1);
	}

	private UUID insertReport(Instant reportedAt) {
		UUID id = UUID.randomUUID();
		jdbc.sql("INSERT INTO weather_field_report (id, neighborhood_code, member_id, axis, kind, reported_at) "
				+ "VALUES (:id, '9900000001', NULL, 'SKY_PRECIPITATION', 'RAIN', :at)")
			.param("id", id)
			.param("at", Timestamp.from(reportedAt))
			.update();
		return id;
	}

	private int memberReports(AuthTestClient.Session session) {
		return jdbc.sql("SELECT count(*) FROM weather_field_report WHERE member_id = :m")
			.param("m", session.memberId().value())
			.query(Integer.class)
			.single();
	}

	private void withdraw(AuthTestClient.Session session) {
		try {
			mvc.perform(delete("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION, session.bearer()))
				.andExpect(status().isNoContent());
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

}
