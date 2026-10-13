package kr.weathertalk.weather;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N7;
import static kr.weathertalk.weather.WeatherStubs.N7_GRID;
import static kr.weathertalk.weather.WeatherStubs.kst;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import kr.weathertalk.IntegrationTest;
import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;
import kr.weathertalk.weather.internal.collect.RetentionCleanupService;
import kr.weathertalk.weather.internal.source.SourceData;
import kr.weathertalk.weather.internal.store.MidTermStore;
import kr.weathertalk.weather.internal.store.ObservationStore;
import kr.weathertalk.weather.internal.store.ShortTermStore;
import kr.weathertalk.weather.internal.store.UvStore;

/**
 * 과거 관측 조회 (FR-WEA-13)와 지난 수집 결과 정리 (FR-WEA-11, legacy 04-#20).
 */
@IntegrationTest
class PastObservationAndCleanupTests extends WeatherTestBase {

	@Autowired
	RetentionCleanupService cleanup;

	@Autowired
	ObservationStore observations;

	@Autowired
	ShortTermStore shortTerms;

	@Autowired
	MidTermStore midTerms;

	@Autowired
	UvStore uv;

	@Test
	void pastObservationUsesStoredValuesWithin48HoursWithoutCollecting() {
		clock.set(kst("2026-10-13T09:15"));
		stubs.observation(N7_GRID, kst("2026-10-13T09:00"), 18.0);
		weatherApi.findCurrentObservation(N7);
		stubs.observation(N7_GRID, kst("2026-10-13T10:00"), 19.0);
		clock.set(kst("2026-10-13T10:15"));
		weatherApi.findCurrentObservation(N7);

		clock.set(kst("2026-10-15T09:00"));
		server.resetRequests();
		CurrentObservation past = weatherApi.findPastObservation(N7, kst("2026-10-13T09:30"));

		assertThat(past.observedAt()).isEqualTo(kst("2026-10-13T09:00"));
		assertThat(past.temperature()).isEqualTo(18.0);
		assertThat(past.weatherFreshness()).isEqualTo(Freshness.FRESH); // 요청 시각(09:30) 기준 판정
		assertThat(past.dayNight()).isEqualTo(DayNight.DAY);
		assertThat(server.findAll(anyRequestedFor(anyUrl()))).isEmpty(); // 과거 시각은 요청 시 수집하지 않는다

		CurrentObservation before = weatherApi.findPastObservation(N7, kst("2026-10-13T07:30"));
		assertThat(before.temperature()).isNull();
		assertThat(before.weatherFreshness()).isEqualTo(Freshness.NONE);

		assertThatThrownBy(() -> weatherApi.findPastObservation(N7, kst("2026-10-15T10:00")))
			.isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST));
	}

	/** legacy 04-#20: 보존 기간(weather.retention.observation=7일)이 지난 이력과 지난 발표분을 지우되 수집 단위별 마지막 정상값은 남긴다. */
	@Test
	void cleanupDeletesExpiredResultsButKeepsLastGoodValuePerUnit_04_20() {
		Instant now = kst("2026-10-20T12:00");
		clock.set(now);
		observations.save(N7_GRID, observation(now.minus(Duration.ofDays(8))), now);
		observations.save(N7_GRID, observation(now.minus(Duration.ofDays(6))), now);
		observations.save(N7_GRID, observation(now.minus(Duration.ofHours(1))), now);
		var lonely = new kr.weathertalk.neighborhood.ForecastGrid(1, 1);
		observations.save(lonely, observation(now.minus(Duration.ofDays(30))), now);

		Instant oldIssue = kst("2026-10-19T05:00");
		Instant latestIssue = kst("2026-10-19T08:00");
		shortTerms.replace(N7_GRID, oldIssue, List.of(slot(kst("2026-10-19T06:00"))), now);
		shortTerms.replace(N7_GRID, latestIssue, List.of(slot(kst("2026-10-19T09:00"))), now);

		midTerms.replaceTemperature(MID_TEMPERATURE_REGION, kst("2026-10-19T18:00"),
				List.of(new SourceData.MidTemperatureDay(LocalDate.of(2026, 10, 23), 1.0, 2.0)), now);
		midTerms.replaceTemperature(MID_TEMPERATURE_REGION, kst("2026-10-20T06:00"),
				List.of(new SourceData.MidTemperatureDay(LocalDate.of(2026, 10, 24), 3.0, 4.0)), now);
		uv.replace(UV_AREA, kst("2026-10-19T18:00"), List.of(new SourceData.UvValue(kst("2026-10-19T18:00"), 0)), now);
		uv.replace(UV_AREA, kst("2026-10-20T06:00"), List.of(new SourceData.UvValue(kst("2026-10-20T06:00"), 3)), now);

		cleanup.cleanup();

		assertThat(count("weather_observation WHERE grid_nx = 98")).isEqualTo(2);
		assertThat(observations.findLatestAtOrBefore(N7_GRID, now.minus(Duration.ofDays(7)))).isEmpty();
		assertThat(count("weather_observation WHERE grid_nx = 1")).isEqualTo(1); // 마지막 정상값
		assertThat(shortTerms.findLatestIssuedAtOrBefore(N7_GRID, now).orElseThrow().issuedAt()).isEqualTo(latestIssue);
		assertThat(count("weather_short_term_issue")).isEqualTo(1);
		assertThat(midTerms.findLatestTemperature(MID_TEMPERATURE_REGION).orElseThrow().issuedAt())
			.isEqualTo(kst("2026-10-20T06:00"));
		assertThat(count("weather_mid_temperature")).isEqualTo(1);
		assertThat(count("weather_uv_index")).isEqualTo(1);
	}

	private int count(String fromWhere) {
		return jdbc.sql("SELECT count(*) FROM " + fromWhere).query(Integer.class).single();
	}

	private static SourceData.Observation observation(Instant at) {
		return new SourceData.Observation(at, 10.0, 50.0, 1.0, PrecipitationAmount.none("mm"), PrecipitationType.NONE);
	}

	private static SourceData.ShortTermSlot slot(Instant at) {
		return new SourceData.ShortTermSlot(at, 10.0, 50.0, 1.0, 0, PrecipitationType.NONE,
				PrecipitationAmount.none("mm"), PrecipitationAmount.none("cm"), SkyCondition.CLEAR, null, null);
	}

}
