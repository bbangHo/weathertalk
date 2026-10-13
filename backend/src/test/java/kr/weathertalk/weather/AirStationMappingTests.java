package kr.weathertalk.weather;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N7;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import kr.weathertalk.IntegrationTest;
import kr.weathertalk.neighborhood.GeoPoint;
import kr.weathertalk.neighborhood.Neighborhood;
import kr.weathertalk.neighborhood.NeighborhoodApi;
import kr.weathertalk.weather.internal.region.AirStationMapper;

/**
 * 동네-측정소 대응 (03 §3.6, legacy 04-#13): 동네 대표 좌표를 에어코리아 측정소 API가 기대하는 TM 좌표로 공식 변환한다.
 */
@IntegrationTest
class AirStationMappingTests extends WeatherTestBase {

	@Autowired
	AirStationMapper mapper;

	@Autowired
	NeighborhoodApi neighborhoodApi;

	/**
	 * 알려진 지점: 서울시청(WGS84 37.5663°N 126.9779°E)의 에어코리아 TM(중부원점 GRS80) 좌표는 약 (198,050, 451,860)이다.
	 * 레거시의 UTM-K(EPSG:5179) 변환은 x가 약 953,000으로 측정소 API 좌표계와 전혀 다른 곳을 가리킨다.
	 * 실제 동네-측정소 쌍(예: 서울 중구 동네 → 중구 측정소)은 운영 키 발급 후 실호출로 한 번 확인한다(테스트는 실서버를 부르지 않는다).
	 */
	@Test
	void convertsRepresentativePointToAirKoreaTmCoordinates_04_13() {
		AirStationMapper.TmPoint tm = mapper.toAirKoreaTm(new GeoPoint(37.5663, 126.9779));

		assertThat(tm.x()).isCloseTo(198_050, within(200.0));
		assertThat(tm.y()).isCloseTo(451_860, within(200.0));
	}

	@Test
	void mapsNearestStationsInOrderUsingConvertedCoordinatesAndKeepsMappingForTtl() {
		stubs.nearbyStations("가까운측정소", "다음측정소", "세번째측정소", "네번째측정소");
		Neighborhood n7 = neighborhoodApi.findNeighborhood(N7).orElseThrow();

		List<String> stations = mapper.stations(n7);

		assertThat(stations).containsExactly("가까운측정소", "다음측정소", "세번째측정소"); // station-fallback-count
		AirStationMapper.TmPoint expected = mapper.toAirKoreaTm(n7.representativePoint());
		LoggedRequest request = server.findAll(getRequestedFor(urlPathEqualTo(WeatherStubs.NEARBY))).get(0);
		assertThat(Double.parseDouble(request.queryParameter("tmX").firstValue())).isCloseTo(expected.x(), within(0.01));
		assertThat(Double.parseDouble(request.queryParameter("tmY").firstValue())).isCloseTo(expected.y(), within(0.01));

		// 대응은 station-mapping-ttl(30일) 동안 다시 정하지 않는다
		mapper.stations(n7);
		assertThat(server.findAll(getRequestedFor(urlPathEqualTo(WeatherStubs.NEARBY)))).hasSize(1);
	}

}
