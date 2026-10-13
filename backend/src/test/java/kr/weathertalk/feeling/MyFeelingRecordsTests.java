package kr.weathertalk.feeling;

import static kr.weathertalk.neighborhood.NeighborhoodTestData.N1;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N2;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.NEAR_N2;
import static kr.weathertalk.weather.WeatherStubs.kst;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import kr.weathertalk.AuthTestClient;
import kr.weathertalk.IntegrationTest;
import kr.weathertalk.member.TemperatureSensitivity;

/**
 * 마이페이지 내 체감 기록·내 체감 패턴 (FR-FEL-11).
 */
@IntegrationTest
class MyFeelingRecordsTests extends FeelingTestBase {

	static final Instant NOW = kst("2026-10-13T09:15");

	/** 최근 feeling.my-records.period(30일) 안의 본인 기록(모든 동네), 기록 시각 내림차순. 패턴은 같은 대상으로 계산한다. */
	@Test
	void listsOwnRecentRecordsAcrossNeighborhoodsNewestFirst() throws Exception {
		clock.set(NOW);
		AuthTestClient.Session session = signup(TemperatureSensitivity.NORMAL);
		AuthTestClient.Session other = signup(TemperatureSensitivity.NORMAL);
		insertRecord(N1, TemperatureSensitivity.NORMAL, NOW.minus(Duration.ofDays(31)), 1, null, null,
				FeelingRecordSource.DIRECT, session.memberId().value(), null);
		insertRecord(N1, TemperatureSensitivity.NORMAL, NOW.minus(Duration.ofDays(29)), 6, null, null,
				FeelingRecordSource.FEEDBACK, session.memberId().value(), null);
		record(other, "VERY_HOT").andExpect(status().isOk());
		clock.set(kst("2026-10-13T09:20"));
		record(session, "COLD").andExpect(status().isOk());
		clock.set(kst("2026-10-13T09:25"));
		record(session, newKey(), "JUST_RIGHT", "HUMID", null, NEAR_N2).andExpect(status().isOk());

		String n2Name = neighborhoodApi.findNeighborhood(N2).orElseThrow().shortName();
		mvc.perform(get("/api/v1/members/me/feeling-records").header(HttpHeaders.AUTHORIZATION, session.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.records.length()").value(3))
			.andExpect(jsonPath("$.records[0].neighborhoodId.value").value(N2.value()))
			.andExpect(jsonPath("$.records[0].neighborhoodName").value(n2Name))
			.andExpect(jsonPath("$.records[0].temperatureTag").value("JUST_RIGHT"))
			.andExpect(jsonPath("$.records[0].humidityTag").value("HUMID"))
			.andExpect(jsonPath("$.records[0].source").value("DIRECT"))
			.andExpect(jsonPath("$.records[0].temperatureSensitivity").value("NORMAL"))
			.andExpect(jsonPath("$.records[1].neighborhoodId.value").value(N1.value()))
			.andExpect(jsonPath("$.records[1].temperatureTag").value("COLD"))
			.andExpect(jsonPath("$.records[2].source").value("FEEDBACK"))
			.andExpect(jsonPath("$.pattern.recordCount").value(3))
			.andExpect(jsonPath("$.pattern.temperatureDistribution.COLD").value(1))
			.andExpect(jsonPath("$.pattern.temperatureDistribution.JUST_RIGHT").value(1))
			.andExpect(jsonPath("$.pattern.temperatureDistribution.HOT").value(1))
			.andExpect(jsonPath("$.pattern.temperatureDistribution.VERY_COLD").value(0))
			.andExpect(jsonPath("$.pattern.tendency").value("INSUFFICIENT_RECORDS"))
			.andExpect(jsonPath("$.pattern.averageDifference").doesNotExist());

		mvc.perform(get("/api/v1/members/me/feeling-records"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("SIGNUP_REQUIRED"));
	}

	/**
	 * 체감 경향: 관측 스냅샷 '확보' 기록마다 (기록 단계 − 기록 시점 그룹의 기준표 예상 단계) 평균. ±0.5 이상이면 춥게/덥게.
	 * '미확보' 기록은 경향 계산에서 빠지고, 확보 기록이 feeling.my-pattern.min-records(5) 미만이면 기록 부족이다.
	 */
	@Test
	void tendencyUsesSecuredSnapshotsAgainstExpectedGroupLevel() {
		clock.set(NOW);
		java.util.UUID member = java.util.UUID.randomUUID();
		// 보통 그룹, 관측 18℃ → 예상 4단계(딱 좋음). 기록 2단계 → 차이 −2
		for (int i = 0; i < 4; i++) {
			insertRecord(N1, TemperatureSensitivity.NORMAL, NOW.minus(Duration.ofDays(i + 1)), 2, null, null,
					FeelingRecordSource.DIRECT, member, 18.0);
		}
		// 미확보 기록은 경향에 쓰지 않는다
		insertRecord(N1, TemperatureSensitivity.NORMAL, NOW.minus(Duration.ofDays(6)), 2, null, null,
				FeelingRecordSource.DIRECT, member, null);
		MyFeelingRecords insufficient = feelingApi.findMyRecords(new kr.weathertalk.shared.Ids.MemberId(member));
		assertThat(insufficient.pattern().recordCount()).isEqualTo(5);
		assertThat(insufficient.pattern().tendencySampleCount()).isEqualTo(4);
		assertThat(insufficient.pattern().tendency()).isEqualTo(MyFeelingRecords.Tendency.INSUFFICIENT_RECORDS);

		// 기록 시점 민감도가 '추위를 많이 탐'이면 같은 18℃도 예상 4단계 → 차이 +1
		insertRecord(N1, TemperatureSensitivity.COLD_SENSITIVE, NOW.minus(Duration.ofDays(7)), 5, null, null,
				FeelingRecordSource.DIRECT, member, 18.0);
		MyFeelingRecords colder = feelingApi.findMyRecords(new kr.weathertalk.shared.Ids.MemberId(member));
		assertThat(colder.pattern().tendencySampleCount()).isEqualTo(5);
		assertThat(colder.pattern().averageDifference()).isEqualTo(-1.4);
		assertThat(colder.pattern().tendency()).isEqualTo(MyFeelingRecords.Tendency.FEELS_COLDER);
		assertThat(colder.pattern().tendencyPhrase()).contains("춥게");

		java.util.UUID similarMember = java.util.UUID.randomUUID();
		for (int i = 0; i < 5; i++) {
			insertRecord(N1, TemperatureSensitivity.HEAT_SENSITIVE, NOW.minus(Duration.ofHours(i + 1)), i % 2 == 0 ? 4 : 5,
					null, null, FeelingRecordSource.DIRECT, similarMember, 18.0);
		}
		MyFeelingRecords similar = feelingApi.findMyRecords(new kr.weathertalk.shared.Ids.MemberId(similarMember));
		assertThat(similar.pattern().averageDifference()).isEqualTo(0.4);
		assertThat(similar.pattern().tendency()).isEqualTo(MyFeelingRecords.Tendency.SIMILAR);
	}

}
