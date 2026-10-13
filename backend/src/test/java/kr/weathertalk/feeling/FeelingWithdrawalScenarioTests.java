package kr.weathertalk.feeling;

import static kr.weathertalk.neighborhood.NeighborhoodTestData.N1;
import static kr.weathertalk.weather.WeatherStubs.kst;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.modulith.test.Scenario;

import kr.weathertalk.AuthTestClient;
import kr.weathertalk.IntegrationTest;
import kr.weathertalk.member.MemberWithdrawn;
import kr.weathertalk.member.TemperatureSensitivity;
import kr.weathertalk.shared.Ids.FeelingRecordId;

/**
 * 탈퇴 → 회원탈퇴함 → 체감 기록 익명화 (FR-FEL-09, ADR 0010). 회원 연결만 끊고 기록은 익명으로 남아 동네 체감에 계속 쓰인다.
 */
@IntegrationTest
class FeelingWithdrawalScenarioTests extends FeelingTestBase {

	@Test
	void withdrawalAnonymizesFeelingRecordsButKeepsThemInNeighborhoodFeeling(Scenario scenario) throws Exception {
		clock.set(kst("2026-10-13T09:15"));
		AuthTestClient.Session session = signup(TemperatureSensitivity.COLD_SENSITIVE);
		FeelingRecordId id = new FeelingRecordId(UUID.fromString(recordId(record(session, newKey(), "COLD", "DRY",
				null, kr.weathertalk.neighborhood.NeighborhoodTestData.INSIDE_N1).andExpect(status().isOk()))));
		assertThat(feelingApi.checkOwnership(session.memberId(), id).owned()).isTrue();

		scenario.stimulate(() -> withdraw(session))
			.andWaitAtMost(Duration.ofSeconds(10))
			.andWaitForStateChange(() -> memberRecordCount(session), count -> count == 0)
			.andVerify(count -> {
				// 소유 확인은 이후 누구에게도 참이 아니다
				assertThat(feelingApi.checkOwnership(session.memberId(), id).owned()).isFalse();
				// 태그·동네·시각·기록 시점 민감도는 그대로 조회된다(회원 ID 없이)
				FeelingRecordView view = feelingApi.findRecords(List.of(id)).get(id);
				assertThat(view.temperatureTag()).isEqualTo(TemperatureTag.COLD);
				assertThat(view.humidityTag()).isEqualTo(HumidityTag.DRY);
				assertThat(view.neighborhoodId()).isEqualTo(N1);
				assertThat(view.temperatureSensitivity()).isEqualTo(TemperatureSensitivity.COLD_SENSITIVE);
				// 동네 체감 집계에 계속 포함된다
				assertThat(feelingApi.findNeighborhoodFeeling(N1, null)
					.groups()
					.get(TemperatureSensitivity.COLD_SENSITIVE)
					.temperature()
					.sampleCount()).isEqualTo(1);
			});

		assertThat(jdbc.sql("SELECT count(*) FROM feeling_record WHERE id = :id AND member_id IS NULL")
			.param("id", id.value())
			.query(Integer.class)
			.single()).isEqualTo(1);
		// 같은 이벤트가 다시 와도 결과가 같다(멱등)
		assertThat(jdbc.sql("UPDATE feeling_record SET member_id = NULL WHERE member_id = :m")
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

	private int memberRecordCount(AuthTestClient.Session session) {
		return jdbc.sql("SELECT count(*) FROM feeling_record WHERE member_id = :m")
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
