package kr.weathertalk.neighborhood;

import static kr.weathertalk.neighborhood.NeighborhoodTestData.N1;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N2;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N3;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N4;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N5;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N6;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N7;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import kr.weathertalk.AuthTestClient;
import kr.weathertalk.IntegrationTest;
import kr.weathertalk.MutableClock;
import kr.weathertalk.neighborhood.internal.dataset.NeighborhoodDatasetLoader;
import kr.weathertalk.shared.Ids.MemberId;
import kr.weathertalk.shared.Ids.NeighborhoodId;
import kr.weathertalk.shared.error.ErrorCode;

/**
 * 관심 동네 등록·해제·순서(FR-NBR-05), 일괄 등록(FR-NBR-06), 동네 검색(FR-NBR-07), 동네 조회.
 */
@IntegrationTest
class InterestNeighborhoodTests {

	@Autowired
	NeighborhoodApi neighborhoodApi;

	@Autowired
	NeighborhoodDatasetLoader loader;

	@Autowired
	MutableClock clock;

	@Autowired
	MockMvc mvc;

	AuthTestClient.Session session;

	@BeforeEach
	void setUp() throws Exception {
		clock.reset();
		NeighborhoodTestData.loadBase(loader);
		session = new AuthTestClient(mvc).signup();
	}

	@AfterEach
	void restoreDataset() {
		NeighborhoodTestData.loadBase(loader);
	}

	@Test
	void addRemoveAndReorderInterests() throws Exception {
		add(N1).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
		add(N2).andExpect(jsonPath("$[1].id").value(N2.value()));
		add(N1).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2)); // 중복은 그대로(멱등)

		mvc.perform(put("/api/v1/members/me/interest-neighborhoods/order").header(HttpHeaders.AUTHORIZATION, session.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"neighborhoodIds\":[\"" + N2.value() + "\",\"" + N1.value() + "\"]}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].id").value(N2.value()));
		mvc.perform(put("/api/v1/members/me/interest-neighborhoods/order").header(HttpHeaders.AUTHORIZATION, session.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"neighborhoodIds\":[\"" + N2.value() + "\"]}"))
			.andExpect(status().isBadRequest());

		remove(N2).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(N1.value()));
		remove(N2).andExpect(status().isOk()); // 없는 동네 해제도 성공(멱등)
		assertThat(neighborhoodApi.findInterestNeighborhoods(session.memberId())).extracting(Neighborhood::id)
			.containsExactly(N1);
	}

	/** BR-NBR-08: 최대 5개, 유효 동네만. */
	@Test
	void interestsAreLimitedAndOnlyValidNeighborhoods() throws Exception {
		for (NeighborhoodId id : List.of(N1, N2, N3, N4, N5)) {
			add(id).andExpect(status().isOk());
		}
		add(N6).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value(ErrorCode.INTEREST_NEIGHBORHOOD_LIMIT_EXCEEDED.name()));

		remove(N5);
		add(new NeighborhoodId("0000000000")).andExpect(status().isNotFound());
		retire(N6);
		add(N6).andExpect(status().isNotFound());
	}

	/** FR-NBR-06: 서버 목록 뒤에 합치고, 이미 있는·폐지·없는 동네는 건너뛰고, 넘치는 건 버린다. 반복해도 결과가 같다. */
	@Test
	void mergeDeviceInterestsAfterSignup() {
		MemberId member = session.memberId();
		neighborhoodApi.mergeInterestNeighborhoods(member, List.of(N1, N2));
		retire(N7);

		List<NeighborhoodId> device = List.of(N2, N7, new NeighborhoodId("0000000000"), N3, N4, N5, N6);
		InterestMergeResult result = neighborhoodApi.mergeInterestNeighborhoods(member, device);

		assertThat(result.interests()).extracting(Neighborhood::id).containsExactly(N1, N2, N3, N4, N5);
		assertThat(result.skipped()).containsExactly(N2, N7, new NeighborhoodId("0000000000"));
		assertThat(result.discarded()).containsExactly(N6);

		InterestMergeResult again = neighborhoodApi.mergeInterestNeighborhoods(member, device);
		assertThat(again.interests()).extracting(Neighborhood::id).containsExactly(N1, N2, N3, N4, N5);
	}

	@Test
	void interestManagementIsMemberOnly() throws Exception {
		mvc.perform(get("/api/v1/members/me/interest-neighborhoods")).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.SIGNUP_REQUIRED.name()));
	}

	/** FR-NBR-07: 유효 동네만, 읍면동 이름 일치 우선, 최소 글자 수 미만이면 빈 결과. */
	@Test
	void searchByName() throws Exception {
		assertThat(neighborhoodApi.search("가상1동")).extracting(Neighborhood::id).first().isEqualTo(N1);
		assertThat(neighborhoodApi.search("다른구")).extracting(Neighborhood::id).containsExactly(N3, N4, N6);
		assertThat(neighborhoodApi.search("")).isEmpty();
		assertThat(neighborhoodApi.search("100%")).isEmpty();

		retire(N3);
		assertThat(neighborhoodApi.search("다른구")).extracting(Neighborhood::id).containsExactly(N4, N6);

		mvc.perform(get("/api/v1/neighborhoods/search").param("q", "바닷가"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].fullName").value("샘플도 먼군 바닷가동"));
		mvc.perform(get("/api/v1/neighborhoods/regions").param("sido", "샘플시"))
			.andExpect(jsonPath("$.names").value(org.hamcrest.Matchers.contains("가상구", "다른구")));
		mvc.perform(get("/api/v1/neighborhoods/regions").param("sido", "샘플시").param("sigungu", "가상구"))
			.andExpect(jsonPath("$.neighborhoods.length()").value(3));
	}

	/** BR-NBR-11: 조회 결과의 이름은 항상 요청된 동네 자신의 이름. */
	@Test
	void bulkLookupReturnsEachNeighborhoodsOwnName() {
		var found = neighborhoodApi.findNeighborhoods(List.of(N1, N7, new NeighborhoodId("0000000000")));

		assertThat(found).hasSize(2);
		assertThat(found.get(N1).eupmyeondongName()).isEqualTo("가상1동");
		assertThat(found.get(N7).eupmyeondongName()).isEqualTo("바닷가동");
	}

	/** 폐지는 적재 버전으로만 일어난다: 해당 동네만 뺀 버전을 적재한다. @AfterEach에서 기준 데이터로 되돌린다. */
	private void retire(NeighborhoodId id) {
		StringBuilder codes = new StringBuilder();
		try (var csv = new java.io.BufferedReader(NeighborhoodTestData.resource(NeighborhoodTestData.CODES))) {
			csv.lines().filter(l -> !l.startsWith(id.value())).forEach(l -> codes.append(l).append('\n'));
		}
		catch (java.io.IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
		loader.load("SAMPLE-test-without-" + id.value(), NeighborhoodTestData.text(codes.toString()));
	}

	private ResultActions add(NeighborhoodId id) throws Exception {
		return mvc.perform(post("/api/v1/members/me/interest-neighborhoods").header(HttpHeaders.AUTHORIZATION, session.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"neighborhoodId\":\"" + id.value() + "\"}"));
	}

	private ResultActions remove(NeighborhoodId id) throws Exception {
		return mvc.perform(delete("/api/v1/members/me/interest-neighborhoods/" + id.value())
			.header(HttpHeaders.AUTHORIZATION, session.bearer()));
	}

}
