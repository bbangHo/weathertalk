package kr.weathertalk.neighborhood;

import static kr.weathertalk.neighborhood.NeighborhoodTestData.AT_SEA;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.INSIDE_N1;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N1;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N2;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N7;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.NEAR_N2;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.EQUIDISTANT_N1_N2;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.location;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;

import kr.weathertalk.AuthTestClient;
import kr.weathertalk.IntegrationTest;
import kr.weathertalk.MutableClock;
import kr.weathertalk.neighborhood.internal.dataset.ForecastGridConverter;
import kr.weathertalk.neighborhood.internal.dataset.NeighborhoodDatasetLoader;
import kr.weathertalk.shared.Ids.MemberId;
import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;

/**
 * 좌표로 동네 판정(FR-NBR-01), 현재 동네 확정(FR-NBR-03)·갱신(FR-NBR-02)·조회(FR-NBR-04).
 * 좌표는 저장·응답 오류 메시지 어디에도 남지 않는다(ADR 0009).
 */
@IntegrationTest
class CurrentNeighborhoodTests {

	@Autowired
	NeighborhoodApi neighborhoodApi;

	@Autowired
	NeighborhoodDatasetLoader loader;

	@Autowired
	MutableClock clock;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ApplicationEvents events;

	@Autowired
	MockMvc mvc;

	@BeforeEach
	void setUp() {
		clock.reset();
		NeighborhoodTestData.loadBase(loader);
	}

	@Test
	void locatesNeighborhoodWithoutStoringAnything() {
		Integer before = jdbc.queryForObject("SELECT count(*) FROM nbr_current_neighborhood", Integer.class);

		Neighborhood found = neighborhoodApi.locate(INSIDE_N1).orElseThrow();

		assertThat(found.id()).isEqualTo(N1);
		assertThat(found.fullName()).isEqualTo("샘플시 가상구 가상1동");
		assertThat(found.shortName()).isEqualTo("가상구 가상1동");
		assertThat(neighborhoodApi.locate(AT_SEA)).isEmpty();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM nbr_current_neighborhood", Integer.class)).isEqualTo(before);
	}

	/**
	 * 경계가 없는 동네는 가장 가까운 대표점으로 판정한다(ADR 0016). 두 대표점에서 같은 거리면 코드가 작은 동네 하나에만
	 * 귀속된다(02 §3.1 불변식).
	 */
	@Test
	void locatesNearestRepresentativePointAndBreaksTiesBySmallerCode() {
		assertThat(neighborhoodApi.locate(EQUIDISTANT_N1_N2)).map(Neighborhood::id).contains(N1);
		assertThat(neighborhoodApi.locate(NEAR_N2)).map(Neighborhood::id).contains(N2);
	}

	/** neighborhood.max-nearest-distance(20km) 안이면 가장 가까운 동네, 밖이면 판정 불가. */
	@Test
	void nearestRepresentativePointBeyondMaxDistanceIsNotResolvable() {
		// N7 대표점(35.105, 129.005)에서 북쪽으로 위도 0.15°(약 16.6km) / 0.2°(약 22.2km)
		assertThat(neighborhoodApi.locate(location(35.255, 129.005, 10.0))).map(Neighborhood::id).contains(N7);
		assertThat(neighborhoodApi.locate(location(35.305, 129.005, 10.0))).isEmpty();
		assertThat(neighborhoodApi.confirmCurrentNeighborhood(MemberId.random(), location(35.305, 129.005, 10.0)))
			.isEqualTo(new CurrentNeighborhoodConfirmation.Rejected(LocationRejection.OUTSIDE_SERVICE_AREA));
	}

	@Test
	void representativePointAndForecastGridComeFromDataset() {
		Neighborhood n1 = neighborhoodApi.findNeighborhood(N1).orElseThrow();
		Neighborhood n7 = neighborhoodApi.findNeighborhood(N7).orElseThrow();

		assertThat(n1.representativePoint()).isEqualTo(new GeoPoint(37.505, 127.005));
		// 파일에 격자가 비어 있으면 대표 좌표로 계산, 있으면 파일 값(계산식과 달라도) 그대로
		assertThat(n1.forecastGrid()).isEqualTo(ForecastGridConverter
			.toGrid(n1.representativePoint().latitude(), n1.representativePoint().longitude()));
		assertThat(n7.forecastGrid()).isEqualTo(new ForecastGrid(98, 76));
	}

	@Test
	void confirmInsideStoresCurrentNeighborhoodAndPublishesChange() {
		MemberId member = MemberId.random();

		Neighborhood confirmed = neighborhoodApi.confirmCurrentNeighborhood(member, INSIDE_N1).orElseThrow();

		assertThat(confirmed.id()).isEqualTo(N1);
		CurrentNeighborhood current = neighborhoodApi.findCurrentNeighborhood(member).orElseThrow();
		assertThat(current.neighborhood().id()).isEqualTo(N1);
		assertThat(current.confirmedAt()).isEqualTo(MutableClock.BASE);
		assertThat(events.stream(CurrentNeighborhoodChanged.class).filter(e -> e.memberId().equals(member)))
			.singleElement()
			.satisfies(e -> {
				assertThat(e.previous()).isNull();
				assertThat(e.current()).isEqualTo(N1);
			});

		// 같은 동네 재확정: 확정 시각만 갱신, 이벤트 없음
		clock.advance(Duration.ofMinutes(3));
		neighborhoodApi.confirmCurrentNeighborhood(member, EQUIDISTANT_N1_N2).orElseThrow();
		assertThat(neighborhoodApi.findCurrentNeighborhood(member).orElseThrow().confirmedAt())
			.isEqualTo(MutableClock.BASE.plus(Duration.ofMinutes(3)));
		assertThat(events.stream(CurrentNeighborhoodChanged.class).filter(e -> e.memberId().equals(member))).hasSize(1);

		// 다른 동네로 이동
		neighborhoodApi.confirmCurrentNeighborhood(member, NEAR_N2).orElseThrow();
		assertThat(events.stream(CurrentNeighborhoodChanged.class).filter(e -> e.memberId().equals(member))).hasSize(2);
	}

	@Test
	void confirmEquidistantPointIsAccepted() {
		MemberId member = MemberId.random();

		assertThat(neighborhoodApi.confirmCurrentNeighborhood(member, EQUIDISTANT_N1_N2).orElseThrow().id())
			.isEqualTo(N1);
	}

	@ParameterizedTest(name = "{0}")
	@CsvSource(nullValues = "null", value = {
			"최대 거리 밖(바다), 37.0, 125.5, 20, OUTSIDE_SERVICE_AREA",
			"정확도 기준 초과, 37.505, 127.005, 500.1, ACCURACY_TOO_LOW",
			"정확도 없음, 37.505, 127.005, null, LOCATION_MISSING",
			"위도 범위 밖, 91.0, 127.005, 20, LOCATION_INVALID",
			"경도 범위 밖, 37.505, 181.0, 20, LOCATION_INVALID",
			"음수 정확도, 37.505, 127.005, -1, LOCATION_INVALID" })
	void confirmRejectsAndKeepsStoredNeighborhood(String name, Double lat, Double lon, Double accuracy,
			LocationRejection reason) {
		MemberId member = MemberId.random();
		neighborhoodApi.confirmCurrentNeighborhood(member, INSIDE_N1).orElseThrow();

		CurrentNeighborhoodConfirmation result = neighborhoodApi.confirmCurrentNeighborhood(member,
				new DeviceLocation(lat, lon, accuracy));

		assertThat(result).isEqualTo(new CurrentNeighborhoodConfirmation.Rejected(reason));
		assertThatThrownBy(result::orElseThrow).isInstanceOfSatisfying(DomainException.class, e -> {
			assertThat(e.code()).isEqualTo(ErrorCode.CURRENT_NEIGHBORHOOD_UNVERIFIED);
			assertThat(e.details()).containsEntry("reason", reason.name());
		});
		assertThat(neighborhoodApi.findCurrentNeighborhood(member).orElseThrow().neighborhood().id()).isEqualTo(N1);
	}

	@Test
	void accuracyExactlyAtLimitIsAccepted() {
		assertThat(neighborhoodApi.confirmCurrentNeighborhood(MemberId.random(), location(37.505, 127.005, 500.0)))
			.isInstanceOf(CurrentNeighborhoodConfirmation.Confirmed.class);
	}

	@Test
	void missingCoordinatesAreRejected() {
		assertThat(neighborhoodApi.confirmCurrentNeighborhood(MemberId.random(), new DeviceLocation(null, 127.0, 10.0)))
			.isEqualTo(new CurrentNeighborhoodConfirmation.Rejected(LocationRejection.LOCATION_MISSING));
		assertThat(neighborhoodApi.confirmCurrentNeighborhood(MemberId.random(), null))
			.isEqualTo(new CurrentNeighborhoodConfirmation.Rejected(LocationRejection.LOCATION_MISSING));
	}

	/** BR-NBR-10, legacy 01-#10: 현재 동네 없음은 정상 상태. 회원 프로필 조회도 동네와 무관하게 성공한다. */
	@Test
	void legacy01_10_noCurrentNeighborhoodIsANormalState() throws Exception {
		AuthTestClient.Session session = new AuthTestClient(mvc).signup();

		assertThat(neighborhoodApi.findCurrentNeighborhood(session.memberId())).isEmpty();
		assertThat(neighborhoodApi.findCurrentNeighborhoods(List.of(session.memberId()))).isEmpty();
		mvc.perform(get("/api/v1/members/me/current-neighborhood").header(HttpHeaders.AUTHORIZATION, session.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.neighborhood").doesNotExist());
		mvc.perform(get("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION, session.bearer()))
			.andExpect(status().isOk());
	}

	/** FR-NBR-02: 최소 간격 안의 반복 갱신은 판정하지 않는다. 판정 불가면 저장된 현재 동네를 바꾸지 않는다. */
	@Test
	void refreshCurrentNeighborhoodHonorsMinIntervalAndKeepsValueWhenUnresolvable() throws Exception {
		AuthTestClient.Session session = new AuthTestClient(mvc).signup();

		refresh(session, "{\"latitude\":37.505,\"longitude\":127.005,\"accuracyMeters\":30}").andExpect(status().isOk())
			.andExpect(jsonPath("$.neighborhood.id").value(N1.value()))
			.andExpect(jsonPath("$.refreshed").value(true));

		clock.advance(Duration.ofMinutes(5));
		refresh(session, "{\"latitude\":37.505,\"longitude\":127.015,\"accuracyMeters\":30}")
			.andExpect(jsonPath("$.neighborhood.id").value(N1.value()))
			.andExpect(jsonPath("$.refreshed").value(false));

		clock.advance(Duration.ofMinutes(5));
		refresh(session, "{\"latitude\":37.505,\"longitude\":127.015,\"accuracyMeters\":30}")
			.andExpect(jsonPath("$.neighborhood.id").value(N2.value()))
			.andExpect(jsonPath("$.refreshed").value(true));

		clock.advance(Duration.ofMinutes(10));
		refresh(session, "{\"latitude\":37.0,\"longitude\":125.5,\"accuracyMeters\":30}")
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value(ErrorCode.NEIGHBORHOOD_NOT_RESOLVABLE.name()));
		assertThat(neighborhoodApi.findCurrentNeighborhood(session.memberId()).orElseThrow().neighborhood().id())
			.isEqualTo(N2);
	}

	/** ADR 0009: 좌표는 저장하지 않고, 오류 응답에도 되풀이하지 않는다. */
	@Test
	void coordinatesAreNeverEchoedOrStored() throws Exception {
		mvc.perform(post("/api/v1/neighborhoods/locate").contentType(MediaType.APPLICATION_JSON)
			.content("{\"latitude\":37.123456,\"longitude\":125.987654}"))
			.andExpect(status().isUnprocessableContent())
			.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("37.123456"))));
		mvc.perform(post("/api/v1/neighborhoods/locate").contentType(MediaType.APPLICATION_JSON)
			.content("{\"latitude\":\"37.654321x\",\"longitude\":125.987654}"))
			.andExpect(status().isBadRequest())
			.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("37.654321"))));
		mvc.perform(post("/api/v1/neighborhoods/locate").contentType(MediaType.APPLICATION_JSON)
			.content("{\"latitude\":95.5,\"longitude\":125.987654}"))
			.andExpect(status().isBadRequest())
			.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("95.5"))));

		List<String> columns = jdbc.queryForList("SELECT column_name FROM information_schema.columns "
				+ "WHERE table_name IN ('nbr_current_neighborhood', 'nbr_interest_neighborhood')", String.class);
		assertThat(columns).noneMatch(c -> c.contains("lat") || c.contains("lon") || c.contains("accuracy"));
		assertThat(INSIDE_N1.toString()).doesNotContain("37.505");
	}

	@Test
	void locateEndpointIsPublic() throws Exception {
		mvc.perform(post("/api/v1/neighborhoods/locate").contentType(MediaType.APPLICATION_JSON)
			.content("{\"latitude\":37.505,\"longitude\":127.005}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(N1.value()))
			.andExpect(jsonPath("$.forecastGrid.nx").isNumber());
	}

	private org.springframework.test.web.servlet.ResultActions refresh(AuthTestClient.Session session, String body)
			throws Exception {
		return mvc.perform(put("/api/v1/members/me/current-neighborhood").header(HttpHeaders.AUTHORIZATION, session.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

}
