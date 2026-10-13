package kr.weathertalk.weather;

import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static kr.weathertalk.weather.WeatherStubs.N7_GRID;
import static kr.weathertalk.weather.WeatherStubs.ncstRequested;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import kr.weathertalk.IntegrationTest;
import kr.weathertalk.MutableClock;
import kr.weathertalk.neighborhood.NeighborhoodApi;
import kr.weathertalk.neighborhood.NeighborhoodTestData;
import kr.weathertalk.shared.Ids.MemberId;
import kr.weathertalk.weather.internal.collect.PreRefreshService;
import kr.weathertalk.weather.internal.region.MidTermRegions;

/**
 * 활성 예보 격자 사전 갱신 (FR-WEA-07)·중기예보 전국 정기 수집 (FR-WEA-08).
 * 다른 테스트의 회원 활동과 겹치지 않도록 먼 미래 시각(기준 + 2000일, 09시대)에서 실행한다.
 */
@IntegrationTest
class PreRefreshTests extends WeatherTestBase {

	@Autowired
	PreRefreshService preRefresh;

	@Autowired
	NeighborhoodApi neighborhoodApi;

	@Autowired
	MidTermRegions midTermRegions;

	Instant nineOClock;

	MemberId member;

	@BeforeEach
	void activateOnlyNeighborhood7() {
		// MutableClock.BASE는 09:00 KST
		nineOClock = MutableClock.BASE.plus(Duration.ofDays(2000)).truncatedTo(ChronoUnit.HOURS);
		clock.set(nineOClock.plus(Duration.ofMinutes(15)));
		member = MemberId.random();
		neighborhoodApi.confirmCurrentNeighborhood(member, NeighborhoodTestData.location(35.105, 129.005, 20.0))
			.orElseThrow();
		assertThat(neighborhoodApi.findActiveForecastGrids()).containsExactly(N7_GRID);
	}

	/** 먼 미래 시각의 활동 기록이 다른 테스트의 활성 동네 판정에 섞이지 않게 지운다. */
	@AfterEach
	void removeFutureActivity() {
		jdbc.sql("DELETE FROM nbr_current_neighborhood WHERE member_id = :id").param("id", member.value()).update();
	}

	@Test
	void collectsOnlyActiveForecastGrids() {
		server.stubFor(get(urlPathEqualTo(WeatherStubs.NCST))
			.willReturn(okJson(WeatherStubs.observationBody(N7_GRID, nineOClock, WeatherStubs.observationValues(15.0)))));
		server.stubFor(get(urlPathEqualTo(WeatherStubs.VILAGE)).willReturn(okJson(WeatherStubs.shortTermBody(N7_GRID,
				nineOClock.minus(Duration.ofHours(1)), 10, at -> java.util.Map.of()))));

		preRefresh.refreshActive();

		assertThat(gridsRequested(WeatherStubs.NCST)).containsExactly("98,76");
		assertThat(gridsRequested(WeatherStubs.VILAGE)).containsExactly("98,76");
		server.verify(exactly(1), getRequestedFor(urlPathEqualTo(WeatherStubs.NCST)));

		// 받을 발표분이 이미 있으면 다음 실행은 외부를 부르지 않는다
		preRefresh.refreshActive();
		server.verify(exactly(1), getRequestedFor(urlPathEqualTo(WeatherStubs.NCST)));
		server.verify(exactly(1), getRequestedFor(urlPathEqualTo(WeatherStubs.VILAGE)));
	}

	/**
	 * FR-WEA-07 4단계: 발표분이 아직 없으면 not-ready-retry-interval(5분) 뒤 다시 시도하고, 발표 + 제공 지연 + not-ready-max-wait(40분)를
	 * 넘기면 그 회차를 포기한다. 요청 시 수집은 포기와 관계없이 시도한다.
	 */
	@Test
	void notReadyIssueIsRetriedAfterIntervalAndGivenUpAfterMaxWait() {
		server.stubFor(get(urlPathEqualTo(WeatherStubs.NCST)).willReturn(okJson(WeatherStubs.kmaNoData())));

		clock.set(nineOClock.plus(Duration.ofMinutes(12)));
		preRefresh.refreshActive();
		server.verify(exactly(1), ncstRequested(N7_GRID, nineOClock));

		clock.set(nineOClock.plus(Duration.ofMinutes(14)));
		preRefresh.refreshActive();
		server.verify(exactly(1), ncstRequested(N7_GRID, nineOClock));

		clock.set(nineOClock.plus(Duration.ofMinutes(18)));
		preRefresh.refreshActive();
		server.verify(exactly(2), ncstRequested(N7_GRID, nineOClock));

		clock.set(nineOClock.plus(Duration.ofMinutes(51)));
		preRefresh.refreshActive();
		server.verify(exactly(2), ncstRequested(N7_GRID, nineOClock));

		weatherApi.findCurrentObservation(NeighborhoodTestData.N7);
		server.verify(exactly(3), ncstRequested(N7_GRID, nineOClock));
	}

	@Test
	void midTermCollectsEveryRegionInTheTable() {
		preRefresh.refreshMidTerm();

		Set<String> land = server.findAll(getRequestedFor(urlPathEqualTo(WeatherStubs.MID_LAND)))
			.stream()
			.map(r -> r.queryParameter("regId").firstValue())
			.collect(Collectors.toSet());
		Set<String> temperature = server.findAll(getRequestedFor(urlPathEqualTo(WeatherStubs.MID_TA)))
			.stream()
			.map(r -> r.queryParameter("regId").firstValue())
			.collect(Collectors.toSet());
		assertThat(land).isEqualTo(midTermRegions.landRegionIds()).contains(MID_LAND_REGION, "11G00000");
		assertThat(temperature).isEqualTo(midTermRegions.temperatureRegionIds());
	}

	private Set<String> gridsRequested(String path) {
		return server.findAll(getRequestedFor(urlPathEqualTo(path)))
			.stream()
			.map(PreRefreshTests::grid)
			.collect(Collectors.toSet());
	}

	private static String grid(LoggedRequest request) {
		return request.queryParameter("nx").firstValue() + "," + request.queryParameter("ny").firstValue();
	}

}
