package kr.weathertalk.home;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.INSIDE_N1;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N1;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N3;
import static kr.weathertalk.weather.WeatherStubs.N7_GRID;
import static kr.weathertalk.weather.WeatherStubs.kst;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import kr.weathertalk.AuthTestClient;
import kr.weathertalk.IntegrationTest;
import kr.weathertalk.neighborhood.NeighborhoodApi;
import kr.weathertalk.weather.WeatherStubs;
import kr.weathertalk.weather.WeatherTestBase;

/**
 * 홈 조합 1차 (07 §1): 보는 동네, 섹션별 상태(정상·데이터 없음·실패), 부분 실패, 비회원 허용.
 */
@IntegrationTest
class HomeCompositionTests extends WeatherTestBase {

	@Autowired
	MockMvc mvc;

	@Autowired
	NeighborhoodApi neighborhoodApi;

	private static boolean warmedUp;

	/**
	 * 이 클래스가 JVM에서 처음 도는 경우 첫 외부 호출(HTTP 클라이언트·JIT 준비)이 섹션 시간 제한을 넘길 수 있어,
	 * 섹션 상태를 검증하기 전에 한 번 데워 둔다.
	 */
	@BeforeEach
	void warmUp() {
		if (!warmedUp) {
			weatherApi.findCurrentObservation(kr.weathertalk.neighborhood.NeighborhoodTestData.N7);
			weatherApi.findDailyForecast(kr.weathertalk.neighborhood.NeighborhoodTestData.N7);
			WeatherStubs.resetAll(jdbc, redis, server);
			warmedUp = true;
		}
	}

	private void stubWeather() {
		stubs.observation(N7_GRID, kst("2026-10-13T09:00"), 18.0);
		stubs.shortTerm(N7_GRID, kst("2026-10-13T08:00"), 63);
		stubs.stationsNearN7("샘플측정소A");
		stubs.measurement("샘플측정소A", new String[] { "2026-10-13 09:00", "45", "20", "2", "2", "1" });
		stubs.uv(UV_AREA, kst("2026-10-13T06:00"), 2, 4);
	}

	@Test
	void guestSeesWeatherSectionsOfRequestedNeighborhood() throws Exception {
		clock.set(kst("2026-10-13T09:15"));
		stubWeather();

		mvc.perform(get("/api/v1/home").param("neighborhoodId", "9900000007"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.neighborhood.id").value("9900000007"))
			.andExpect(jsonPath("$.neighborhood.eupmyeondongName").value("바닷가동"))
			.andExpect(jsonPath("$.neighborhood.current").value(false))
			.andExpect(jsonPath("$.currentWeather.status").value("OK"))
			.andExpect(jsonPath("$.currentWeather.data.temperature").value(18.0))
			.andExpect(jsonPath("$.currentWeather.data.weatherFreshness").value("FRESH"))
			.andExpect(jsonPath("$.currentWeather.data.dayNight").value("DAY"))
			.andExpect(jsonPath("$.currentWeather.data.uv.gradeLabel").value("보통"))
			.andExpect(jsonPath("$.hourlyForecast.status").value("OK"))
			.andExpect(jsonPath("$.hourlyForecast.data.slots.length()").value(24))
			.andExpect(jsonPath("$.dailyForecast.status").value("OK"))
			.andExpect(jsonPath("$.dailyForecast.data.days[0].source").value("SHORT_TERM"));
	}

	/** 07 §1.4-2: 모듈이 정상 응답했으나 값이 없으면 '데이터 없음'(실패와 구분). 외부 원천 전부 장애여도 화면은 뜬다. */
	@Test
	void sectionsWithoutDataAreEmptyNotFailed() throws Exception {
		clock.set(kst("2026-10-13T09:15"));

		mvc.perform(get("/api/v1/home").param("neighborhoodId", "9900000007"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.currentWeather.status").value("EMPTY"))
			.andExpect(jsonPath("$.currentWeather.data.weatherFreshness").value("NONE"))
			.andExpect(jsonPath("$.hourlyForecast.status").value("EMPTY"))
			.andExpect(jsonPath("$.dailyForecast.status").value("EMPTY"));
	}

	/** 07 §1.4-1: 섹션마다 home.section-timeout(테스트 2초)을 적용한다. 느린 섹션만 실패이고 나머지 섹션은 정상으로 응답한다. */
	@Test
	void slowSectionFailsAloneWithoutBlockingOtherSections() throws Exception {
		clock.set(kst("2026-10-13T09:15"));
		stubWeather();
		// 현재 날씨 섹션만 부르는 실황·측정(두 측정소)·자외선을 느리게: 호출마다 외부 제한시간(0.4초)×재시도 2회를 소진해
		// 섹션 전체가 약 3.2초 걸린다
		stubs.stationsNearN7("샘플측정소A", "샘플측정소B");
		server.stubFor(WeatherStubs.ncstRequest(N7_GRID, kst("2026-10-13T09:00"))
			.willReturn(okJson(WeatherStubs.kmaNoData()).withFixedDelay(2000)));
		server.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathEqualTo(WeatherStubs.REALTIME))
			.willReturn(okJson("{}").withFixedDelay(2000)));
		server.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathEqualTo(WeatherStubs.UV))
			.willReturn(okJson("{}").withFixedDelay(2000)));

		mvc.perform(get("/api/v1/home").param("neighborhoodId", "9900000007"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.currentWeather.status").value("FAILED"))
			.andExpect(jsonPath("$.currentWeather.failure").value("TIMEOUT"))
			.andExpect(jsonPath("$.hourlyForecast.status").value("OK"))
			.andExpect(jsonPath("$.dailyForecast.status").value("OK"));

		// 시간 초과로 잘린 섹션의 조회가 끝날 때까지 기다려 다음 테스트의 모의 서버 기록에 섞이지 않게 한다
		Thread.sleep(2500);
	}

	/** legacy 04-#18: 응답의 동네 이름은 항상 조회 대상 동네다. 회원은 동네를 지정하지 않으면 자기 현재 동네를 본다. */
	@Test
	void memberSeesCurrentNeighborhoodByDefaultAndViewedNeighborhoodNameOtherwise_04_18() throws Exception {
		clock.set(kst("2026-10-13T09:15"));
		AuthTestClient.Session session = new AuthTestClient(mvc).signup();
		neighborhoodApi.confirmCurrentNeighborhood(session.memberId(), INSIDE_N1).orElseThrow();
		String n1Name = neighborhoodApi.findNeighborhood(N1).orElseThrow().eupmyeondongName();
		String n3Name = neighborhoodApi.findNeighborhood(N3).orElseThrow().eupmyeondongName();

		mvc.perform(get("/api/v1/home").header(HttpHeaders.AUTHORIZATION, session.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.neighborhood.id").value(N1.value()))
			.andExpect(jsonPath("$.neighborhood.eupmyeondongName").value(n1Name))
			.andExpect(jsonPath("$.neighborhood.current").value(true));

		mvc.perform(get("/api/v1/home").param("neighborhoodId", N3.value())
			.header(HttpHeaders.AUTHORIZATION, session.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.neighborhood.id").value(N3.value()))
			.andExpect(jsonPath("$.neighborhood.eupmyeondongName").value(n3Name))
			.andExpect(jsonPath("$.neighborhood.current").value(false))
			.andExpect(jsonPath("$.currentWeather.data.neighborhoodId.value").value(N3.value()));
	}

	/** 07 §1.4-4: 보는 동네를 정할 수 없을 때만 화면 전체 실패. 잘못된 토큰은 비회원으로 낮추지 않는다(07 §1.2). */
	@Test
	void unresolvableNeighborhoodIsTheOnlyWholeScreenFailure() throws Exception {
		mvc.perform(get("/api/v1/home"))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("VIEWING_NEIGHBORHOOD_UNRESOLVED"))
			.andExpect(jsonPath("$.reason").value("NOT_SPECIFIED"));
		mvc.perform(get("/api/v1/home").param("neighborhoodId", "0000000000"))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.reason").value("NOT_FOUND"));
		mvc.perform(get("/api/v1/home").param("neighborhoodId", "9900000007")
			.header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));
	}

}
