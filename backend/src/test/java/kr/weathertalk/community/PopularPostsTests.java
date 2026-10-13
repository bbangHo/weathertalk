package kr.weathertalk.community;

import static kr.weathertalk.neighborhood.NeighborhoodTestData.N1;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N2;
import static kr.weathertalk.weather.WeatherStubs.kst;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import kr.weathertalk.AuthTestClient;
import kr.weathertalk.IntegrationTest;
import kr.weathertalk.home.HomeApi;
import kr.weathertalk.home.HomeSection;
import kr.weathertalk.home.HomeView;
import kr.weathertalk.member.Requester;

/**
 * 동네 인기글 (FR-COM-12, BR-COM-13, legacy 03-#17) + 홈 인기글 섹션(07 §1.2, home.popular-posts-count).
 * 창(community.popular.window = 24시간) 안에 작성된 글 중 좋아요 수(community.popular.min-likes = 1 이상) 내림차순, 같으면 최신순,
 * 최대 community.popular.size(5)건. 모자라면 모자란 대로 — 최신글로 채우지 않는다.
 */
@IntegrationTest
class PopularPostsTests extends CommunityTestBase {

	static final Instant NOW = kst("2026-10-13T09:15");

	@Autowired
	CommunityApi communityApi;

	@Autowired
	HomeApi homeApi;

	private List<UUID> popularIds(Requester requester) {
		return communityApi.findPopularPosts(N1, requester).stream().map(PostSummary::postId).toList();
	}

	@Test
	void legacy03_17_popularIsRankedByLikesWithinWindowNotByRecency() throws Exception {
		clock.set(NOW);
		AuthTestClient.Session author = member();
		UUID me = author.memberId().value();
		UUID a = insertPost(me, N1, NOW.minus(Duration.ofHours(1)), UUID.randomUUID());
		UUID b = insertPost(me, N1, NOW.minus(Duration.ofHours(2)), UUID.randomUUID());
		UUID c = insertPost(me, N1, NOW.minus(Duration.ofMinutes(30)), UUID.randomUUID());
		UUID edgeOfWindow = insertPost(me, N1, NOW.minus(Duration.ofHours(24)), UUID.randomUUID());
		UUID tooOld = insertPost(me, N1, NOW.minus(Duration.ofHours(24)).minusSeconds(1), UUID.randomUUID());
		UUID noLikes = insertPost(me, N1, NOW, UUID.randomUUID());
		UUID otherNeighborhood = insertPost(me, N2, NOW, UUID.randomUUID());
		insertLikes(a, 3);
		insertLikes(b, 5);
		insertLikes(c, 5);
		insertLikes(edgeOfWindow, 1);
		insertLikes(tooOld, 10);
		insertLikes(otherNeighborhood, 10);

		assertThat(popularIds(Requester.GUEST)).containsExactly(c, b, a, edgeOfWindow).doesNotContain(noLikes);

		// 개수는 community.popular.size(5)
		for (int i = 0; i < 3; i++) {
			insertLikes(insertPost(me, N1, NOW.minusSeconds(i), UUID.randomUUID()), 2);
		}
		assertThat(popularIds(Requester.GUEST)).hasSize(5).startsWith(c, b, a);

		mvc.perform(get("/api/v1/community/neighborhoods/" + N1.value() + "/popular-posts"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(5))
			.andExpect(jsonPath("$[0].postId").value(c.toString()))
			.andExpect(jsonPath("$[0].likeCount").value(5));
	}

	@Test
	void popularPostsOfUnknownAuthorsAreSkippedAndEmptyIsNotFilled() throws Exception {
		clock.set(NOW);
		AuthTestClient.Session author = member();
		insertPost(author.memberId().value(), N1, NOW, UUID.randomUUID());
		assertThat(popularIds(Requester.GUEST)).isEmpty();

		UUID ghost = insertPost(UUID.randomUUID(), N1, NOW, UUID.randomUUID());
		insertLikes(ghost, 9);
		List<UUID> real = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			UUID id = insertPost(author.memberId().value(), N1, NOW.minusSeconds(i), UUID.randomUUID());
			insertLikes(id, 1);
			real.add(id);
		}
		assertThat(popularIds(Requester.GUEST)).containsExactlyElementsOf(real);
	}

	/** home: 인기글 섹션은 community 선정 그대로 최대 home.popular-posts-count건, 0건이면 EMPTY(가짜 카드 없음). */
	@Test
	void homeShowsPopularPostsSection() throws Exception {
		clock.set(NOW);
		HomeView empty = homeApi.compose(Requester.GUEST, N1, null, null);
		assertThat(empty.popularPosts().status()).isEqualTo(HomeSection.Status.EMPTY);
		assertThat(empty.popularPosts().data()).isEmpty();

		AuthTestClient.Session author = member();
		AuthTestClient.Session viewer = member();
		UUID liked = newPost(author, "좋아요 받은 글");
		like(viewer, liked, true).andExpect(status().isOk());

		HomeView view = homeApi.compose(new Requester.Member(viewer.memberId(), false, false), N1, null, null);
		assertThat(view.popularPosts().status()).isEqualTo(HomeSection.Status.OK);
		assertThat(view.popularPosts().data()).singleElement().satisfies(post -> {
			assertThat(post.postId()).isEqualTo(liked);
			assertThat(post.likedByViewer()).isTrue();
			assertThat(post.mine()).isFalse();
		});
		mvc.perform(get("/api/v1/home").param("neighborhoodId", N1.value()))
			.andExpect(jsonPath("$.popularPosts.status").value("OK"))
			.andExpect(jsonPath("$.popularPosts.data[0].postId").value(liked.toString()));
	}

}
