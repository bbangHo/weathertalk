package kr.weathertalk.weather.internal.source;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static kr.weathertalk.weather.WeatherStubs.kst;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import kr.weathertalk.neighborhood.ForecastGrid;
import kr.weathertalk.weather.AirGrade;
import kr.weathertalk.weather.PrecipitationType;
import kr.weathertalk.weather.WeatherStubs;
import kr.weathertalk.weather.internal.config.WeatherProperties;
import kr.weathertalk.weather.internal.config.WeatherSourceProperties;

/**
 * 외부 원천 HTTP 어댑터 (03 §3.1, BR-WEA-06): 요청 인자 형식, 응답 해석, 결측, 장애·지연·미제공, 키 미설정.
 */
class WeatherSourceAdapterTests {

	static final ForecastGrid GRID = new ForecastGrid(60, 127);

	static WireMockServer server;

	final ExternalCalls calls = new ExternalCalls(
			new WeatherProperties.External(Duration.ofMillis(300), 2, Duration.ofMillis(5)));

	@BeforeAll
	static void start() {
		server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
		server.start();
	}

	@AfterAll
	static void stop() {
		server.stop();
	}

	@BeforeEach
	void reset() {
		server.resetAll();
	}

	KmaHttpSource kma(String key) {
		String base = server.baseUrl() + "/kma";
		return new KmaHttpSource(new WeatherSourceProperties.Kma(WeatherSourceProperties.KmaProvider.DATA_GO_KR, base, key),
				HttpSourceSupport.client(base, Duration.ofMillis(300)), calls);
	}

	AirKoreaHttpSource airKorea(String key) {
		String base = server.baseUrl() + "/air";
		return new AirKoreaHttpSource(new WeatherSourceProperties.AirKorea(base, key),
				HttpSourceSupport.client(base, Duration.ofMillis(300)), calls);
	}

	/** 키가 비어 있으면 호출하지 않고 '키 없음'으로 끝난다(기동은 정상, 수집 시 없음 처리). */
	@Test
	void missingKeyDoesNotCallSource() {
		assertThatThrownBy(() -> kma("").fetchObservation(GRID, kst("2026-10-13T09:00")))
			.isInstanceOf(SourceException.KeyMissing.class);
		assertThatThrownBy(() -> airKorea(null).fetchMeasurements("중구")).isInstanceOf(SourceException.KeyMissing.class);
		assertThat(kma(" ").configured()).isFalse();
		server.verify(exactly(0), anyRequestedFor(anyUrl()));
	}

	/** 인증키의 '+', '/', '='가 값 단위로 인코딩돼 원문 그대로 전달된다. 시각 인자는 0 채움 규격(yyyyMMdd, HHmm). */
	@Test
	void sendsKeyAndTimeArgumentsInSpecFormat() {
		server.stubFor(get(urlPathEqualTo(WeatherStubs.NCST)).willReturn(okJson(WeatherStubs.observationBody(GRID,
				kst("2026-10-13T02:00"), Map.of("T1H", "3.5", "PTY", "5", "RN1", "0.4")))));

		SourceData.Observation o = kma("abc+def/ghi==").fetchObservation(GRID, kst("2026-10-13T02:00"));

		LoggedRequest request = server.findAll(getRequestedFor(urlPathEqualTo(WeatherStubs.NCST))).get(0);
		assertThat(request.queryParameter("serviceKey").firstValue()).isEqualTo("abc+def/ghi==");
		assertThat(request.getUrl()).contains("serviceKey=abc%2Bdef%2Fghi%3D%3D");
		assertThat(request.queryParameter("base_date").firstValue()).isEqualTo("20261013");
		assertThat(request.queryParameter("base_time").firstValue()).isEqualTo("0200");
		assertThat(request.queryParameter("dataType").firstValue()).isEqualTo("JSON");
		assertThat(o.temperature()).isEqualTo(3.5);
		assertThat(o.precipitationType()).isEqualTo(PrecipitationType.RAIN); // 실황 5(빗방울) → 비
		assertThat(o.precipitation().label()).isEqualTo("0.4mm");
		assertThat(o.humidity()).isNull(); // 항목 누락 → 없음
	}

	/** legacy 04-#11: 자외선 시각 인자는 yyyyMMddHH(시 0 채움). h0, h3, …은 발표 시각 + h시간 값. */
	@Test
	void uvRequestUsesZeroPaddedHourAndParsesHourlyValues_04_11() {
		new WeatherStubs(server).uv("1111000000", kst("2026-10-13T06:00"), 1, 3, 6);

		List<SourceData.UvValue> values = kma("key").fetchUv("1111000000", kst("2026-10-13T06:00"));

		assertThat(server.findAll(getRequestedFor(urlPathEqualTo(WeatherStubs.UV))).get(0).queryParameter("time")
			.firstValue()).isEqualTo("2026101306");
		assertThat(values).extracting(SourceData.UvValue::forecastAt)
			.containsExactly(kst("2026-10-13T06:00"), kst("2026-10-13T09:00"), kst("2026-10-13T12:00"));
		assertThat(values).extracting(SourceData.UvValue::value).containsExactly(1, 3, 6);
	}

	/** legacy 04-#23: 24시간을 넘는 단기예보 응답을 날짜+시각 키로 묶는다(같은 시각 다른 날이 섞이지 않음). */
	@Test
	void shortTermSlotsAreKeyedByDateAndTime_04_23() {
		server.stubFor(get(urlPathEqualTo(WeatherStubs.VILAGE)).willReturn(okJson(WeatherStubs.shortTermBody(GRID,
				kst("2026-10-13T20:00"), 30, at -> Map.of("TMP", String.valueOf(at.getDayOfMonth()))))));

		List<SourceData.ShortTermSlot> slots = kma("key").fetchShortTerm(GRID, kst("2026-10-13T20:00"));

		assertThat(slots).hasSize(30);
		assertThat(slots).filteredOn(s -> s.forecastAt().equals(kst("2026-10-13T21:00")))
			.singleElement()
			.extracting(SourceData.ShortTermSlot::temperature)
			.isEqualTo(13.0);
		assertThat(slots).filteredOn(s -> s.forecastAt().equals(kst("2026-10-14T21:00")))
			.singleElement()
			.extracting(SourceData.ShortTermSlot::temperature)
			.isEqualTo(14.0);
	}

	/** 결과 코드 03(자료 없음)은 미제공 — 즉시 재시도하지 않는다. */
	@Test
	void noDataIsNotReadyAndNotRetried() {
		server.stubFor(get(urlPathEqualTo(WeatherStubs.NCST)).willReturn(okJson(WeatherStubs.kmaNoData())));

		assertThatThrownBy(() -> kma("key").fetchObservation(GRID, kst("2026-10-13T09:00")))
			.isInstanceOf(SourceException.NotReady.class);
		server.verify(exactly(1), getRequestedFor(urlPathEqualTo(WeatherStubs.NCST)));
	}

	/** 형식 오류(공공데이터포털 XML 오류 응답)·서버 오류·시간 초과는 같은 실패로 다루고 max-attempts만큼 같은 인자로 재시도한다. */
	@Test
	void malformedErrorAndTimeoutAreRetriedFailures() {
		server.stubFor(get(urlPathEqualTo(WeatherStubs.NCST)).willReturn(aResponse().withStatus(200)
			.withBody("<OpenAPI_ServiceResponse><cmmMsgHeader><returnReasonCode>30</returnReasonCode></cmmMsgHeader></OpenAPI_ServiceResponse>")));
		assertThatThrownBy(() -> kma("key").fetchObservation(GRID, kst("2026-10-13T09:00")))
			.isInstanceOf(SourceException.Failure.class);
		server.verify(exactly(2), getRequestedFor(urlPathEqualTo(WeatherStubs.NCST)));

		server.resetAll();
		server.stubFor(get(urlPathEqualTo(WeatherStubs.NCST)).willReturn(okJson(WeatherStubs.kmaNoData())
			.withFixedDelay(1000)));
		assertThatThrownBy(() -> kma("key").fetchObservation(GRID, kst("2026-10-13T09:00")))
			.isInstanceOf(SourceException.Failure.class);
		server.verify(exactly(2), getRequestedFor(urlPathEqualTo(WeatherStubs.NCST)));

		server.resetAll();
		server.stubFor(get(urlPathEqualTo(WeatherStubs.NCST)).willReturn(okJson(
				"{\"response\":{\"header\":{\"resultCode\":\"22\",\"resultMsg\":\"LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR\"}}}")));
		assertThatThrownBy(() -> kma("key").fetchObservation(GRID, kst("2026-10-13T09:00")))
			.isInstanceOf(SourceException.Failure.class)
			.hasMessageContaining("22");
	}

	@Test
	void transientFailureIsRetriedWithSameIssueTime() {
		server.stubFor(get(urlPathEqualTo(WeatherStubs.NCST)).inScenario("retry")
			.whenScenarioStateIs(Scenario.STARTED)
			.willReturn(aResponse().withStatus(503))
			.willSetStateTo("recovered"));
		server.stubFor(get(urlPathEqualTo(WeatherStubs.NCST)).inScenario("retry")
			.whenScenarioStateIs("recovered")
			.willReturn(okJson(WeatherStubs.observationBody(GRID, kst("2026-10-13T09:00"), Map.of("T1H", "12.0")))));

		assertThat(kma("key").fetchObservation(GRID, kst("2026-10-13T09:00")).temperature()).isEqualTo(12.0);
		assertThat(server.findAll(getRequestedFor(urlPathEqualTo(WeatherStubs.NCST))))
			.extracting(r -> r.queryParameter("base_time").firstValue())
			.containsExactly("0900", "0900");
	}

	/** 에어코리아: "-"는 결측, 자정은 전날 "24:00", 등급 결측이면 농도로 오염물질별 공식 경계 판정. 최신 순. */
	@Test
	void airKoreaMeasurementsParseMissingMidnightAndGradeFallback() {
		new WeatherStubs(server).measurements("중구",
				List.of(new String[] { "2026-10-12 23:00", "50", "20", "2", "2", "1" },
						new String[] { "2026-10-12 24:00", "-", "40", "-", "-", "2" }));

		List<SourceData.AirMeasurement> measurements = airKorea("key").fetchMeasurements("중구");

		assertThat(measurements.get(0).measuredAt()).isEqualTo(kst("2026-10-13T00:00"));
		assertThat(measurements.get(0).pm10()).isNull();
		assertThat(measurements.get(0).pm10Grade()).isNull();
		assertThat(measurements.get(0).pm25Grade()).isEqualTo(AirGrade.BAD); // 40 → PM2.5 나쁨(36~75)
		assertThat(measurements.get(1).measuredAt()).isEqualTo(kst("2026-10-12T23:00"));
		assertThat(measurements.get(1).pm10Grade()).isEqualTo(AirGrade.MODERATE);
	}

	@Test
	void nearbyStationsAreOrderedByDistance() {
		server.stubFor(get(urlPathEqualTo(WeatherStubs.NEARBY)).willReturn(okJson(WeatherStubs.airItems(List.of(
				WeatherStubs.item("stationName", "먼곳", "tm", 5.2), WeatherStubs.item("stationName", "가까운곳", "tm", 0.8))))));

		assertThat(airKorea("key").findNearbyStations(198050.0, 451860.0)).containsExactly("가까운곳", "먼곳");
		LoggedRequest request = server.findAll(getRequestedFor(urlPathEqualTo(WeatherStubs.NEARBY))).get(0);
		assertThat(request.queryParameter("tmX").firstValue()).isEqualTo("198050.0");
	}

}
