package kr.weathertalk.weather;

import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N1;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N6;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N7;
import static kr.weathertalk.weather.WeatherStubs.kst;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import kr.weathertalk.IntegrationTest;
import kr.weathertalk.neighborhood.Neighborhood;
import kr.weathertalk.neighborhood.NeighborhoodApi;
import kr.weathertalk.weather.internal.collect.PreRefreshService;
import kr.weathertalk.weather.internal.region.AirStationMapper;
import kr.weathertalk.weather.internal.region.AirStationSync;
import kr.weathertalk.weather.internal.source.AirKoreaCallCounter;

/**
 * 에어코리아 측정소 목록 동기화와 동네-측정소 대응 (03 §3.6, 구현 메모 §13).
 * <ul>
 * <li>측정소 목록(getMsrstnList)의 WGS84 좌표를 그대로 저장하고, 동네 대표 좌표에서 가까운 순을 PostGIS로 계산한다(legacy 04-#13:
 * 레거시는 측정소 검색 좌표계를 잘못 변환해 엉뚱한 측정소를 골랐다).</li>
 * <li>목록 동기화는 신설·폐지를 반영하고, 대응은 저장하지 않아 다음 조회부터 바로 바뀐다.</li>
 * <li>대기질은 시도별 실시간 측정(전국) 1회로 모든 동네가 값을 얻는다.</li>
 * </ul>
 */
@IntegrationTest
class AirStationMappingTests extends WeatherTestBase {

	/** 실제 측정소 좌표(WGS84, 에어코리아 측정소 목록 기준 근사). 종로구는 측정소정보 기술문서 v1.2의 응답 예시 값이다. */
	static final Map<String, double[]> KNOWN = Map.of(
			"종로구", new double[] { 127.005028, 37.572025 },
			"서초구", new double[] { 126.994474, 37.504547 },
			"강남구", new double[] { 127.047470, 37.517528 });

	@Autowired
	AirStationMapper mapper;

	@Autowired
	AirStationSync sync;

	@Autowired
	PreRefreshService preRefresh;

	@Autowired
	AirKoreaCallCounter counter;

	@Autowired
	NeighborhoodApi neighborhoodApi;

	private void stubKnownStations(String... names) {
		stubs.stationList(List.of(names).stream().map(n -> WeatherStubs.stationItem(n, KNOWN.get(n)[0], KNOWN.get(n)[1])).toList());
	}

	private Neighborhood neighborhood(kr.weathertalk.shared.Ids.NeighborhoodId id) {
		return neighborhoodApi.findNeighborhood(id).orElseThrow();
	}

	/**
	 * 동네 1(37.505°N 127.005°E): 서초구 약 0.9km, 강남구 약 4.0km, 종로구 약 7.4km.
	 * 동네 6(37.515°N 127.025°E): 강남구 약 2.0km, 서초구 약 2.9km, 종로구 약 6.6km.
	 */
	@Test
	void nearestStationsAreComputedLocallyFromSyncedWgs84Coordinates_04_13() {
		stubKnownStations("종로구", "서초구", "강남구");

		sync.syncIfDue();

		assertThat(mapper.stations(neighborhood(N1), false)).containsExactly("서초구", "강남구", "종로구");
		assertThat(mapper.stations(neighborhood(N6), false)).containsExactly("강남구", "서초구", "종로구");
		// 좌표는 기술문서대로 WGS84(dmX=경도, dmY=위도) 그대로 저장된다
		double[] stored = jdbc.sql("""
				SELECT ST_X(location::geometry) AS lon, ST_Y(location::geometry) AS lat
				FROM weather_air_station WHERE station_name = '종로구'
				""").query((rs, n) -> new double[] { rs.getDouble("lon"), rs.getDouble("lat") }).single();
		assertThat(stored[0]).isCloseTo(127.005028, within(1e-9));
		assertThat(stored[1]).isCloseTo(37.572025, within(1e-9));
		// 동네가 몇 개든 측정소 대응에 외부 호출은 없다(목록 1회뿐)
		mapper.stations(neighborhood(N7), true);
		server.verify(exactly(1), getRequestedFor(urlPathEqualTo(WeatherStubs.STATION_LIST)));
	}

	@Test
	void fallbackCountLimitsCandidates() {
		stubs.stationsNearN7("가까운측정소", "다음측정소", "세번째측정소", "네번째측정소");
		sync.syncIfDue();

		assertThat(mapper.stations(neighborhood(N7), false)).containsExactly("가까운측정소", "다음측정소", "세번째측정소");
	}

	/** 목록 동기화: 신설은 추가, 목록에서 빠진 측정소는 폐지로 표시돼 대응에서 빠지고, 다시 나타나면 되살아난다. 주기 전에는 다시 받지 않는다. */
	@Test
	void syncReflectsNewAndClosedStationsAndRunsOncePerInterval() {
		clock.set(kst("2026-10-13T04:07"));
		stubKnownStations("종로구", "서초구");
		sync.syncIfDue();
		assertThat(mapper.stations(neighborhood(N1), false)).containsExactly("서초구", "종로구");

		// 동기화 주기(1일) 안에서는 다시 받지 않는다
		clock.set(kst("2026-10-13T20:07"));
		stubKnownStations("종로구", "강남구");
		sync.syncIfDue();
		server.verify(exactly(1), getRequestedFor(urlPathEqualTo(WeatherStubs.STATION_LIST)));

		// 주기가 지나면 받는다: 서초구 폐지, 강남구 신설
		clock.set(kst("2026-10-14T04:07"));
		sync.syncIfDue();
		assertThat(mapper.stations(neighborhood(N1), false)).containsExactly("강남구", "종로구");
		assertThat(jdbc.sql("SELECT closed_at IS NOT NULL FROM weather_air_station WHERE station_name = '서초구'")
			.query(Boolean.class)
			.single()).isTrue();

		// 서초구가 다시 목록에 나타나면 대응에 돌아온다
		clock.set(kst("2026-10-15T04:07"));
		stubKnownStations("종로구", "서초구", "강남구");
		sync.syncIfDue();
		assertThat(mapper.stations(neighborhood(N1), false)).containsExactly("서초구", "강남구", "종로구");
	}

	/** 목록이 비어 있으면 요청 경로에서 1회 받는다. 실패하면 실패 백오프(5분) 안에는 다시 부르지 않는다. */
	@Test
	void emptyListIsFetchedOnDemandWithFailureBackoff() {
		clock.set(kst("2026-10-13T09:15"));
		assertThat(mapper.stations(neighborhood(N7), true)).isEmpty(); // 목록 원천 미정의 → 404 실패
		mapper.stations(neighborhood(N7), true);
		server.verify(exactly(2), getRequestedFor(urlPathEqualTo(WeatherStubs.STATION_LIST))); // max-attempts(2) 1회분

		stubs.stationsNearN7("샘플측정소A");
		clock.set(kst("2026-10-13T09:21"));
		assertThat(mapper.stations(neighborhood(N7), true)).containsExactly("샘플측정소A");
	}

	/**
	 * 시도별 실시간 측정(전국) 1회로 여러 동네가 각자 가까운 측정소 값을 얻는다. 같은 시의 사전 갱신도 다시 부르지 않는다.
	 */
	@Test
	void oneNationwideRealtimeCallServesManyNeighborhoods() {
		clock.set(kst("2026-10-13T09:25"));
		stubKnownStations("종로구", "서초구", "강남구");
		stubs.measurement("서초구", new String[] { "2026-10-13 09:00", "25", "10", "1", "1", "1" });
		stubs.measurement("강남구", new String[] { "2026-10-13 09:00", "90", "40", "3", "3", "2" });
		stubs.measurement("종로구", new String[] { "2026-10-13 09:00", "45", "20", "2", "2", "1" });

		AirQuality n1 = weatherApi.findCurrentObservation(N1).airQuality();
		AirQuality n6 = weatherApi.findCurrentObservation(N6).airQuality();
		AirQuality n7 = weatherApi.findCurrentObservation(N7).airQuality();

		assertThat(n1.stationName()).isEqualTo("서초구");
		assertThat(n1.combinedGrade()).isEqualTo(AirGrade.GOOD);
		assertThat(n6.stationName()).isEqualTo("강남구");
		assertThat(n6.combinedGrade()).isEqualTo(AirGrade.BAD);
		assertThat(n6.measuredAt()).isEqualTo(kst("2026-10-13T09:00"));
		assertThat(n6.freshness()).isEqualTo(Freshness.FRESH);
		// 동네 7(부산 앞바다 가상 동네)도 가장 가까운 측정소(이 목록에서는 강남구)의 값을 받는다
		assertThat(n7.stationName()).isNotNull();
		server.verify(exactly(1), getRequestedFor(urlPathEqualTo(WeatherStubs.REALTIME)));
		server.verify(exactly(1), getRequestedFor(urlPathEqualTo(WeatherStubs.STATION_LIST)));

		preRefresh.refreshActive();
		server.verify(exactly(1), getRequestedFor(urlPathEqualTo(WeatherStubs.REALTIME)));
		assertThat(counter.today("getCtprvnRltmMesureDnsty")).isEqualTo(1);
		assertThat(counter.today("getMsrstnList")).isEqualTo(1);
	}

}
