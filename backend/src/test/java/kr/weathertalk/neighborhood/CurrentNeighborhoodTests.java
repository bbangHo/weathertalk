package kr.weathertalk.neighborhood;

import static kr.weathertalk.neighborhood.NeighborhoodTestData.N1;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N2;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N7;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.UNKNOWN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import kr.weathertalk.shared.Ids.NeighborhoodId;
import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;

/**
 * 현재 동네 확정(FR-NBR-03)·변경·조회(FR-NBR-04). 좌표는 서버로 오지 않는다 — 앱이 기기 안에서 판정하고
 * 사용자가 확인한 동네 ID만 받는다(ADR 0009). 서버는 동네가 있고 유효한지만 검사한다.
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

	@AfterEach
	void restore() {
		NeighborhoodTestData.loadBase(loader);
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

		Neighborhood confirmed = neighborhoodApi.confirmCurrentNeighborhood(member, N1).orElseThrow();

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
		neighborhoodApi.confirmCurrentNeighborhood(member, N1).orElseThrow();
		assertThat(neighborhoodApi.findCurrentNeighborhood(member).orElseThrow().confirmedAt())
			.isEqualTo(MutableClock.BASE.plus(Duration.ofMinutes(3)));
		assertThat(events.stream(CurrentNeighborhoodChanged.class).filter(e -> e.memberId().equals(member))).hasSize(1);

		// 다른 동네로 이동
		neighborhoodApi.confirmCurrentNeighborhood(member, N2).orElseThrow();
		assertThat(events.stream(CurrentNeighborhoodChanged.class).filter(e -> e.memberId().equals(member))).hasSize(2);
	}

	/** 없는 동네·폐지된 동네·동네 ID 없음은 거부하고, 저장된 현재 동네를 바꾸지 않는다. */
	@Test
	void confirmRejectsAndKeepsStoredNeighborhood() {
		MemberId member = MemberId.random();
		neighborhoodApi.confirmCurrentNeighborhood(member, N1).orElseThrow();
		// N1·N2만 남기고 나머지(N7 포함)를 폐지한 버전
		loader.load("SAMPLE-test-retire", NeighborhoodTestData.text(NeighborhoodDatasetLoaderTests.HEADER
				+ "9900000001,샘플시,가상구,가상1동,,,127.005,37.505,\n"
				+ "9900000002,샘플시,가상구,가상2동,,,127.015,37.505,\n"));

		assertRejected(member, UNKNOWN, LocationRejection.NEIGHBORHOOD_UNKNOWN);
		assertRejected(member, N7, LocationRejection.NEIGHBORHOOD_RETIRED);
		assertRejected(member, new NeighborhoodId(null), LocationRejection.NEIGHBORHOOD_MISSING);
		assertRejected(member, new NeighborhoodId(" "), LocationRejection.NEIGHBORHOOD_MISSING);
		assertRejected(member, null, LocationRejection.NEIGHBORHOOD_MISSING);
		assertThat(neighborhoodApi.findCurrentNeighborhood(member).orElseThrow().neighborhood().id()).isEqualTo(N1);
	}

	private void assertRejected(MemberId member, NeighborhoodId id, LocationRejection reason) {
		CurrentNeighborhoodConfirmation result = neighborhoodApi.confirmCurrentNeighborhood(member, id);

		assertThat(result).isEqualTo(new CurrentNeighborhoodConfirmation.Rejected(reason));
		assertThatThrownBy(result::orElseThrow).isInstanceOfSatisfying(DomainException.class, e -> {
			assertThat(e.code()).isEqualTo(ErrorCode.CURRENT_NEIGHBORHOOD_UNVERIFIED);
			assertThat(e.details()).containsEntry("reason", reason.name());
		});
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

	/** 사용자가 확인한 동네로 현재 동네 변경: 동네 ID로 바로 저장하고, 없는 동네면 저장된 값을 바꾸지 않는다. */
	@Test
	void changeCurrentNeighborhoodByConfirmedNeighborhoodId() throws Exception {
		AuthTestClient.Session session = new AuthTestClient(mvc).signup();

		change(session, "{\"neighborhoodId\":\"" + N1.value() + "\"}").andExpect(status().isOk())
			.andExpect(jsonPath("$.neighborhood.id").value(N1.value()))
			.andExpect(jsonPath("$.confirmedAt").isNotEmpty());

		clock.advance(Duration.ofMinutes(1));
		change(session, "{\"neighborhoodId\":\"" + N2.value() + "\"}").andExpect(status().isOk())
			.andExpect(jsonPath("$.neighborhood.id").value(N2.value()));

		change(session, "{\"neighborhoodId\":\"" + UNKNOWN.value() + "\"}")
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value(ErrorCode.CURRENT_NEIGHBORHOOD_UNVERIFIED.name()))
			.andExpect(jsonPath("$.reason").value("NEIGHBORHOOD_UNKNOWN"));
		change(session, "{}").andExpect(jsonPath("$.reason").value("NEIGHBORHOOD_MISSING"));
		assertThat(neighborhoodApi.findCurrentNeighborhood(session.memberId()).orElseThrow().neighborhood().id())
			.isEqualTo(N2);
	}

	/** 좌표를 받는 판정 API는 없다(ADR 0009). 현재 동네 저장소에도 좌표 열이 없다. */
	@Test
	void serverNeverReceivesOrStoresCoordinates() throws Exception {
		mvc.perform(post("/api/v1/neighborhoods/locate").contentType(MediaType.APPLICATION_JSON)
			.content("{\"latitude\":37.505,\"longitude\":127.005}"))
			.andExpect(status().is4xxClientError());

		List<String> columns = jdbc.queryForList("SELECT column_name FROM information_schema.columns "
				+ "WHERE table_name IN ('nbr_current_neighborhood', 'nbr_interest_neighborhood')", String.class);
		assertThat(columns).noneMatch(c -> c.contains("lat") || c.contains("lon") || c.contains("accuracy"));
	}

	private org.springframework.test.web.servlet.ResultActions change(AuthTestClient.Session session, String body)
			throws Exception {
		return mvc.perform(put("/api/v1/members/me/current-neighborhood").header(HttpHeaders.AUTHORIZATION, session.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

}
