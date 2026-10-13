package kr.weathertalk.community;

import static kr.weathertalk.neighborhood.NeighborhoodTestData.N1;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N2;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.NEAR_N2;
import static kr.weathertalk.weather.WeatherStubs.kst;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.jayway.jsonpath.JsonPath;

import kr.weathertalk.AuthTestClient;
import kr.weathertalk.IntegrationTest;
import kr.weathertalk.shared.Ids.NeighborhoodId;

/**
 * 피드 커서 페이징 (FR-COM-05, BR-COM-08·14·15, legacy 03-#1·#2).
 * 정렬 키 (작성 시각 내림차순, ID 내림차순), 커서보다 엄격히 작은 것만 — 같은 시각 동률·중간 삭제·새 글에도 중복·누락 없음.
 */
@IntegrationTest
class FeedPagingTests extends CommunityTestBase {

	static final Instant NOW = kst("2026-10-13T09:15");

	record Page(List<UUID> ids, String nextCursor) {
	}

	private Page page(AuthTestClient.Session viewer, NeighborhoodId neighborhood, String cursor, Integer size)
			throws Exception {
		String json = body(feed(viewer, neighborhood, cursor, size).andExpect(status().isOk()));
		List<String> ids = JsonPath.read(json, "$.items[*].postId");
		return new Page(ids.stream().map(UUID::fromString).toList(), JsonPath.read(json, "$.nextCursor"));
	}

	private static List<UUID> descending(List<UUID> ids) {
		// PostgreSQL uuid 정렬(바이트 순 = 문자열 순)과 같게 비교한다
		return ids.stream().sorted(Comparator.comparing(UUID::toString).reversed()).toList();
	}

	/** legacy 03-#1: 같은 작성 시각의 글 5개를 2개씩 넘겨도 중복·누락 없이 ID 내림차순으로 이어진다. */
	@Test
	void legacy03_1_tiesOnCreatedAtArePagedByIdWithoutDuplicatesOrGaps() throws Exception {
		clock.set(NOW);
		AuthTestClient.Session author = member();
		List<UUID> ids = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			ids.add(insertPost(author.memberId().value(), N1, NOW, UUID.randomUUID()));
		}
		Page first = page(null, N1, null, 2);
		Page second = page(null, N1, first.nextCursor(), 2);
		Page third = page(null, N1, second.nextCursor(), 2);

		List<UUID> seen = new ArrayList<>(first.ids());
		seen.addAll(second.ids());
		seen.addAll(third.ids());
		assertThat(seen).containsExactlyElementsOf(descending(ids));
		assertThat(first.ids()).hasSize(2);
		assertThat(third.ids()).hasSize(1);
		assertThat(third.nextCursor()).isNull();
	}

	/** legacy 03-#1: 페이지 사이에 글이 지워지거나 새 글이 생겨도 다음 페이지는 중복·누락 없이 이어진다. */
	@Test
	void legacy03_1_deletionAndNewPostsBetweenPagesDoNotShiftThePage() throws Exception {
		clock.set(NOW);
		AuthTestClient.Session author = member();
		List<UUID> ids = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			ids.add(insertPost(author.memberId().value(), N1, NOW.minusSeconds(i), UUID.randomUUID()));
		}
		Page first = page(author, N1, null, 2);
		assertThat(first.ids()).containsExactly(ids.get(0), ids.get(1));

		// 이미 본 글(1번째)과 아직 안 본 글(4번째)을 지우고, 더 최신 글을 하나 쓴다
		jdbc.sql("DELETE FROM community_post WHERE id IN (:ids)").param("ids", List.of(ids.get(0), ids.get(3))).update();
		insertPost(author.memberId().value(), N1, NOW.plusSeconds(10), UUID.randomUUID());

		Page second = page(author, N1, first.nextCursor(), 2);
		assertThat(second.ids()).containsExactly(ids.get(2), ids.get(4));
		Page third = page(author, N1, second.nextCursor(), 2);
		assertThat(third.ids()).containsExactly(ids.get(5));
		assertThat(third.nextCursor()).isNull();
		// 새 글은 첫 페이지 재조회(새로고침)로 본다
		assertThat(page(author, N1, null, 2).ids()).hasSize(2).doesNotContain(ids.get(0));
	}

	/** legacy 03-#2: 목록 크기·다음 여부는 실제 값 — 0건, 딱 나누어떨어지는 마지막 페이지, 최대 개수 초과는 최대값으로. */
	@Test
	void legacy03_2_pageSizeAndNextCursorAreActualValues() throws Exception {
		clock.set(NOW);
		AuthTestClient.Session author = member();
		feed(null, N1, null, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(0))
			.andExpect(jsonPath("$.nextCursor").doesNotExist());

		for (int i = 0; i < 4; i++) {
			insertPost(author.memberId().value(), N1, NOW.minusSeconds(i), UUID.randomUUID());
		}
		Page first = page(null, N1, null, 2);
		Page last = page(null, N1, first.nextCursor(), 2);
		assertThat(last.ids()).hasSize(2);
		assertThat(last.nextCursor()).isNull();

		for (int i = 0; i < 50; i++) {
			insertPost(author.memberId().value(), N1, NOW.minus(Duration.ofMinutes(10 + i)), UUID.randomUUID());
		}
		Page capped = page(null, N1, null, 1000);
		assertThat(capped.ids()).hasSize(50);
		assertThat(capped.nextCursor()).isNotNull();
		assertThat(page(null, N1, null, null).ids()).hasSize(20);
		feed(null, N1, null, 0).andExpect(status().isBadRequest());
	}

	@Test
	void malformedCursorAndUnknownNeighborhoodAreRejected() throws Exception {
		feed(null, N1, "not-a-cursor", null).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		feed(null, new NeighborhoodId("0000000000"), null, null).andExpect(status().isNotFound());
	}

	/**
	 * BR-COM-14: 작성자를 찾을 수 없는(탈퇴 처리 대기 등) 글은 빼지만 커서는 그 행까지 지나가므로 다음 페이지가 밀리지 않는다.
	 * BR-COM-15: 다른 동네 피드도 볼 수 있고, 동네마다 따로다.
	 */
	@Test
	void postsOfUnknownAuthorsAreOmittedWithoutBreakingThePaging() throws Exception {
		clock.set(NOW);
		AuthTestClient.Session author = member();
		UUID a = insertPost(author.memberId().value(), N1, NOW, UUID.randomUUID());
		insertPost(UUID.randomUUID(), N1, NOW.minusSeconds(1), UUID.randomUUID());
		UUID c = insertPost(author.memberId().value(), N1, NOW.minusSeconds(2), UUID.randomUUID());
		UUID other = insertPost(author.memberId().value(), N2, NOW, UUID.randomUUID());

		Page first = page(null, N1, null, 2);
		assertThat(first.ids()).containsExactly(a);
		assertThat(first.nextCursor()).isNotNull();
		assertThat(page(null, N1, first.nextCursor(), 2).ids()).containsExactly(c);
		assertThat(page(null, N2, null, null).ids()).containsExactly(other);
	}

	/** FR-COM-16: 내가 쓴 글 — 모든 동네, 피드와 같은 정렬·커서. 게시글의 동네명을 함께 준다. 회원 전용. */
	@Test
	void myPostsSpanAllNeighborhoods() throws Exception {
		clock.set(NOW);
		AuthTestClient.Session author = member();
		AuthTestClient.Session someoneElse = member();
		UUID inN1 = newPost(author, "동네 1에서");
		neighborhoodApi.confirmCurrentNeighborhood(author.memberId(), NEAR_N2).orElseThrow();
		clock.advance(Duration.ofMinutes(1));
		UUID inN2 = postId(createPost(author, newKey(), "동네 2에서", List.of(), null, NEAR_N2));
		newPost(someoneElse, "남의 글");

		String json = body(mvc.perform(auth(get("/api/v1/members/me/posts").param("size", "1"), author))
			.andExpect(status().isOk()));
		assertThat(JsonPath.<String>read(json, "$.items[0].postId")).isEqualTo(inN2.toString());
		assertThat(JsonPath.<String>read(json, "$.items[0].neighborhood.id")).isEqualTo(N2.value());
		assertThat(JsonPath.<Boolean>read(json, "$.items[0].mine")).isTrue();
		String next = JsonPath.read(json, "$.nextCursor");
		mvc.perform(auth(get("/api/v1/members/me/posts").param("cursor", next), author))
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].postId").value(inN1.toString()))
			.andExpect(jsonPath("$.nextCursor").doesNotExist());
		mvc.perform(get("/api/v1/members/me/posts")).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("SIGNUP_REQUIRED"));
	}

}
