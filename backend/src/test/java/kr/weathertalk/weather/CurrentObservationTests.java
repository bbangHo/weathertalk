package kr.weathertalk.weather;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.moreThanOrExactly;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N7;
import static kr.weathertalk.weather.WeatherStubs.N7_GRID;
import static kr.weathertalk.weather.WeatherStubs.kst;
import static kr.weathertalk.weather.WeatherStubs.ncstRequested;
import static kr.weathertalk.weather.WeatherStubs.vilageRequested;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import kr.weathertalk.IntegrationTest;

/**
 * 현재 관측 조회 (FR-WEA-01), 요청 시 수집 (FR-WEA-06), 신선도 (BR-WEA-03~05), 동시 수집 방지 (BR-WEA-07).
 */
@IntegrationTest
class CurrentObservationTests extends WeatherTestBase {

	private void stubNormalSources() {
		stubs.observation(N7_GRID, kst("2026-10-13T09:00"),
				Map.of("T1H", "18.3", "REH", "60", "WSD", "2.1", "RN1", "0", "PTY", "0"));
		stubs.shortTerm(N7_GRID, kst("2026-10-13T08:00"), 72, at -> at.getHour() == 9 ? Map.of("SKY", "3") : Map.of());
		stubs.stationsNearN7("샘플측정소A", "샘플측정소B");
		stubs.measurement("샘플측정소A", new String[] { "2026-10-13 09:00", "45", "20", "2", "2", "1" });
		stubs.uv(UV_AREA, kst("2026-10-13T06:00"), 2, 4, 6, 3);
	}

	@Test
	void returnsObservationWithItemSourcesFreshnessAirUvAndDayNight() {
		clock.set(kst("2026-10-13T09:15"));
		stubNormalSources();

		CurrentObservation o = weatherApi.findCurrentObservation(N7);

		assertThat(o.neighborhoodId()).isEqualTo(N7);
		assertThat(o.observedAt()).isEqualTo(kst("2026-10-13T09:00"));
		assertThat(o.temperature()).isEqualTo(18.3);
		assertThat(o.humidity()).isEqualTo(60.0);
		assertThat(o.windSpeed()).isEqualTo(2.1);
		assertThat(o.precipitationType()).isEqualTo(PrecipitationType.NONE);
		assertThat(o.precipitation().label()).isEqualTo("없음");
		assertThat(o.precipitationPhrase()).isEqualTo("강수 없음");
		assertThat(o.sky()).isEqualTo(SkyCondition.MOSTLY_CLOUDY);
		assertThat(o.feelsLike()).isEqualTo(18.3); // 10월·10℃ 초과: 공식 미적용 구간
		assertThat(o.weatherFreshness()).isEqualTo(Freshness.FRESH);
		assertThat(o.dayNight()).isEqualTo(DayNight.DAY);
		assertThat(o.sources()).containsEntry(ObservationItem.TEMPERATURE, ValueSource.OBSERVATION)
			.containsEntry(ObservationItem.SKY, ValueSource.FORECAST)
			.containsEntry(ObservationItem.FEELS_LIKE, ValueSource.OBSERVATION)
			.containsEntry(ObservationItem.AIR_QUALITY, ValueSource.MEASUREMENT)
			.containsEntry(ObservationItem.UV, ValueSource.MEASUREMENT);

		AirQuality air = o.airQuality();
		assertThat(air.stationName()).isEqualTo("샘플측정소A");
		assertThat(air.measuredAt()).isEqualTo(kst("2026-10-13T09:00"));
		assertThat(air.pm10()).isEqualTo(45.0);
		assertThat(air.pm25()).isEqualTo(20.0);
		assertThat(air.combinedGrade()).isEqualTo(AirGrade.MODERATE);
		assertThat(air.ozoneGrade()).isEqualTo(AirGrade.GOOD);
		assertThat(air.freshness()).isEqualTo(Freshness.FRESH);

		// 09:15는 06시 발표분의 h3(09시) 값
		assertThat(o.uv().value()).isEqualTo(4);
		assertThat(o.uv().grade()).isEqualTo(UvGrade.MODERATE);
		assertThat(o.uv().gradeLabel()).isEqualTo("보통");
		assertThat(o.uv().issuedAt()).isEqualTo(kst("2026-10-13T06:00"));
		// 04-#11: 자외선 시각 인자는 yyyyMMddHH, 시 두 자리 0 채움
		server.verify(getRequestedFor(urlPathEqualTo(WeatherStubs.UV)).withQueryParam("time", equalTo("2026101306")));
	}

	@Test
	void missingValuesStayNoneInsteadOfZero() {
		clock.set(kst("2026-10-13T09:15"));
		// 결측 표기(-999), 항목 누락, 미정의 강수 형태 코드
		stubs.observation(N7_GRID, kst("2026-10-13T09:00"), Map.of("T1H", "-999", "WSD", "1.0", "RN1", "-", "PTY", "9"));
		stubs.stationsNearN7("샘플측정소A");
		stubs.measurement("샘플측정소A", new String[] { "2026-10-13 09:00", "-", "12", "-", "-", "-" });

		CurrentObservation o = weatherApi.findCurrentObservation(N7);

		assertThat(o.temperature()).isNull();
		assertThat(o.humidity()).isNull();
		assertThat(o.windSpeed()).isEqualTo(1.0);
		assertThat(o.precipitation()).isNull();
		assertThat(o.precipitationType()).isNull();
		assertThat(o.precipitationPhrase()).isNull();
		assertThat(o.feelsLike()).isNull();
		assertThat(o.sources()).doesNotContainKeys(ObservationItem.TEMPERATURE, ObservationItem.HUMIDITY,
				ObservationItem.PRECIPITATION_TYPE, ObservationItem.FEELS_LIKE);
		assertThat(o.airQuality().pm10()).isNull();
		assertThat(o.airQuality().pm10Grade()).isNull();
		assertThat(o.airQuality().pm25()).isEqualTo(12.0);
		assertThat(o.airQuality().pm25Grade()).isEqualTo(AirGrade.GOOD); // 등급 결측 → PM2.5 공식 경계로 판정
		assertThat(o.airQuality().combinedGrade()).isEqualTo(AirGrade.GOOD);
		// 자외선 원천 실패 → '정보 없음'
		assertThat(o.uv().value()).isNull();
		assertThat(o.uv().gradeLabel()).isEqualTo(UvGrade.NO_DATA_LABEL);
	}

	/** legacy 04-#8: 신선도는 관측 시각으로만 판정하며 조회가 유효 기간을 늘리지 않는다. */
	@Test
	void freshnessDependsOnlyOnObservationTimeNotOnReads_04_8() {
		clock.set(kst("2026-10-13T09:15"));
		stubNormalSources();
		assertThat(weatherApi.findCurrentObservation(N7).weatherFreshness()).isEqualTo(Freshness.FRESH);

		// 10:05 — 10시 실황은 제공 지연(10분) 전이라 받을 최신은 여전히 09시: 정상, 외부 호출 없음
		clock.set(kst("2026-10-13T10:05"));
		for (int i = 0; i < 3; i++) {
			assertThat(weatherApi.findCurrentObservation(N7).weatherFreshness()).isEqualTo(Freshness.FRESH);
		}
		server.verify(exactly(1), ncstRequested(N7_GRID, kst("2026-10-13T09:00")));
		server.verify(exactly(0), ncstRequested(N7_GRID, kst("2026-10-13T10:00")));

		// 10:15 — 10시 실황 수집 실패: 마지막 정상값(09시)을 '지연'으로
		stubs.observationResponse(N7_GRID, kst("2026-10-13T10:00"), WeatherStubs.serverError());
		clock.set(kst("2026-10-13T10:15"));
		CurrentObservation delayed = weatherApi.findCurrentObservation(N7);
		assertThat(delayed.weatherFreshness()).isEqualTo(Freshness.DELAYED);
		assertThat(delayed.observedAt()).isEqualTo(kst("2026-10-13T09:00"));
		assertThat(delayed.temperature()).isEqualTo(18.3);
		server.verify(exactly(2), ncstRequested(N7_GRID, kst("2026-10-13T10:00"))); // max-attempts(테스트 2회)

		// 실패 백오프(5분) 안에서는 다시 부르지 않는다
		clock.set(kst("2026-10-13T10:17"));
		weatherApi.findCurrentObservation(N7);
		server.verify(exactly(2), ncstRequested(N7_GRID, kst("2026-10-13T10:00")));

		// 11:01 — 09시 관측이 2시간(max-staleness)을 넘었다: 실황 '없음' → 같은 시각 예보 칸으로 채우고 출처 '예보'
		clock.set(kst("2026-10-13T11:01"));
		CurrentObservation none = weatherApi.findCurrentObservation(N7);
		assertThat(none.weatherFreshness()).isEqualTo(Freshness.NONE);
		assertThat(none.observedAt()).isEqualTo(kst("2026-10-13T11:00"));
		assertThat(none.temperature()).isEqualTo(11.0); // 모의 예보: 10 + (시 % 10)
		assertThat(none.sources()).containsEntry(ObservationItem.TEMPERATURE, ValueSource.FORECAST)
			.containsEntry(ObservationItem.FEELS_LIKE, ValueSource.FORECAST);
	}

	/** legacy 04-#9, BR-WEA-02: 제공 지연 전에는 직전 발표 시각을 요청한다. 00:00~02:XX의 단기예보는 전날 23시. */
	@Test
	void requestsLatestIssueAfterAvailabilityDelay_04_9() {
		// 08:05: 실황 08시·단기 08시 발표분은 제공 지연(10분) 전 → 실황 07시, 단기 05시를 받는다
		clock.set(kst("2026-10-13T08:05"));
		weatherApi.findCurrentObservation(N7);
		server.verify(moreThanOrExactly(1), ncstRequested(N7_GRID, kst("2026-10-13T07:00")));
		server.verify(exactly(0), ncstRequested(N7_GRID, kst("2026-10-13T08:00")));
		server.verify(moreThanOrExactly(1), vilageRequested(N7_GRID, kst("2026-10-13T05:00")));
		server.verify(exactly(0), vilageRequested(N7_GRID, kst("2026-10-13T08:00")));

		WeatherStubs.resetAll(jdbc, redis, server);
		clock.set(kst("2026-10-14T02:05"));
		weatherApi.findHourlyForecast(N7, null);
		server.verify(moreThanOrExactly(1), vilageRequested(N7_GRID, kst("2026-10-13T23:00")));
	}

	/**
	 * BR-WEA-06: 최신 발표분이 비어 있고 저장값이 없으면 직전 발표분을 '대체'로 받는다.
	 * 미제공 발표분은 not-ready-retry-interval(5분) 뒤에만 다시 시도한다 — 매 요청 재시도 폭주 방지(legacy 04-#9).
	 */
	@Test
	void notReadyIssueIsSubstitutedOnceAndRetriedOnlyAfterInterval() {
		clock.set(kst("2026-10-13T09:15"));
		stubs.observationNoData(N7_GRID, kst("2026-10-13T09:00"));
		stubs.observation(N7_GRID, kst("2026-10-13T08:00"), 17.0);

		CurrentObservation substituted = weatherApi.findCurrentObservation(N7);
		assertThat(substituted.observedAt()).isEqualTo(kst("2026-10-13T08:00"));
		assertThat(substituted.temperature()).isEqualTo(17.0);
		assertThat(substituted.weatherFreshness()).isEqualTo(Freshness.DELAYED);

		clock.set(kst("2026-10-13T09:17"));
		weatherApi.findCurrentObservation(N7);
		weatherApi.findCurrentObservation(N7);
		server.verify(exactly(1), ncstRequested(N7_GRID, kst("2026-10-13T09:00")));

		stubs.observation(N7_GRID, kst("2026-10-13T09:00"), 18.0);
		clock.set(kst("2026-10-13T09:21"));
		CurrentObservation fresh = weatherApi.findCurrentObservation(N7);
		assertThat(fresh.temperature()).isEqualTo(18.0);
		assertThat(fresh.weatherFreshness()).isEqualTo(Freshness.FRESH);
		server.verify(exactly(2), ncstRequested(N7_GRID, kst("2026-10-13T09:00")));
	}

	/** BR-WEA-07: 같은 수집 단위·발표 시각에 동시 요청이 몰려도 외부 호출은 한 번. 나머지는 결과를 기다려 같은 값을 받는다. */
	@Test
	void concurrentRequestsCollectOnlyOnce() throws Exception {
		clock.set(kst("2026-10-13T09:15"));
		stubNormalSources();
		stubs.observationResponse(N7_GRID, kst("2026-10-13T09:00"), okJson(WeatherStubs.observationBody(N7_GRID,
				kst("2026-10-13T09:00"), WeatherStubs.observationValues(18.0))).withFixedDelay(300));

		int requests = 8;
		CountDownLatch start = new CountDownLatch(1);
		List<Future<CurrentObservation>> results = new ArrayList<>();
		try (ExecutorService executor = Executors.newFixedThreadPool(requests)) {
			for (int i = 0; i < requests; i++) {
				results.add(executor.submit(() -> {
					start.await();
					return weatherApi.findCurrentObservation(N7);
				}));
			}
			start.countDown();
			for (Future<CurrentObservation> result : results) {
				CurrentObservation o = result.get();
				assertThat(o.temperature()).isEqualTo(18.0);
				assertThat(o.weatherFreshness()).isEqualTo(Freshness.FRESH);
			}
		}
		server.verify(exactly(1), ncstRequested(N7_GRID, kst("2026-10-13T09:00")));
		server.verify(exactly(1), vilageRequested(N7_GRID, kst("2026-10-13T08:00")));
		server.verify(exactly(1), getRequestedFor(urlPathEqualTo(WeatherStubs.REALTIME)));
		server.verify(exactly(1), getRequestedFor(urlPathEqualTo(WeatherStubs.UV)));
	}

	/** legacy 04-#4: 대기질 갱신 실패가 이전 값을 지우지 않는다. 마지막 유효 측정값과 측정 시각을 함께 응답한다. */
	@Test
	void airQualityKeepsLastValueAndMeasuredTimeWhenRefreshFails_04_4() {
		clock.set(kst("2026-10-13T09:15"));
		stubNormalSources();
		weatherApi.findCurrentObservation(N7);

		stubs.realtimeResponse(WeatherStubs.serverError());
		clock.set(kst("2026-10-13T10:25"));
		AirQuality air = weatherApi.findCurrentObservation(N7).airQuality();

		assertThat(air.pm10()).isEqualTo(45.0);
		assertThat(air.measuredAt()).isEqualTo(kst("2026-10-13T09:00"));
		assertThat(air.freshness()).isEqualTo(Freshness.DELAYED);
	}

	/** 03 §3.6: 가장 가까운 측정소의 최신 측정이 결측이면 다음 측정소로 넘어간다. */
	@Test
	void fallsBackToNextStationWhenNearestMeasurementIsMissing() {
		clock.set(kst("2026-10-13T09:15"));
		stubs.stationsNearN7("샘플측정소A", "샘플측정소B");
		stubs.measurement("샘플측정소A", new String[] { "2026-10-13 09:00", "-", "-", "-", "-", "-" });
		stubs.measurement("샘플측정소B", new String[] { "2026-10-13 09:00", "90", "40", "3", "3", "2" });

		AirQuality air = weatherApi.findCurrentObservation(N7).airQuality();

		assertThat(air.stationName()).isEqualTo("샘플측정소B");
		assertThat(air.combinedGrade()).isEqualTo(AirGrade.BAD);
	}

	/** BR-WEA-23: 밤에는 자외선을 비우고(수집도 하지 않음) 낮·밤 값은 weather가 준다. */
	@Test
	void uvIsEmptyAtNight() {
		clock.set(kst("2026-10-13T19:30"));
		CurrentObservation o = weatherApi.findCurrentObservation(N7);

		assertThat(o.dayNight()).isEqualTo(DayNight.NIGHT);
		assertThat(o.uv()).isNull();
		server.verify(exactly(0), getRequestedFor(urlPathEqualTo(WeatherStubs.UV)));
	}

}
