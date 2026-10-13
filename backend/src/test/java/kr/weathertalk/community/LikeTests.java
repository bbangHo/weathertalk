package kr.weathertalk.community;

import static kr.weathertalk.weather.WeatherStubs.kst;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;

import kr.weathertalk.AuthTestClient;
import kr.weathertalk.IntegrationTest;
import kr.weathertalk.IntegrationTestConfiguration;
import kr.weathertalk.member.MemberApi;

/**
 * 좋아요 (FR-COM-11, BR-COM-10·11, legacy 03-#9·#10).
 */
@IntegrationTest
class LikeTests extends CommunityTestBase {

	static final Instant NOW = kst("2026-10-13T09:15");

	@Autowired
	ApplicationEvents events;

	@Autowired
	MemberApi memberApi;

	@Autowired
	IntegrationTestConfiguration.ThreadSafeEventRecorder recorder;

	/** legacy 03-#10: 같은 회원이 '좋아요'를 동시에 여러 번 보내도 1건만 남고 모두 성공, 좋아요함은 1번만. */
	@Test
	void legacy03_10_concurrentLikeRequestsAreIdempotent() throws Exception {
		clock.set(NOW);
		AuthTestClient.Session author = member();
		AuthTestClient.Session fan = member();
		UUID postId = newPost(author, "인기 많을 글");

		int threads = 8;
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Integer>> results = new ArrayList<>();
		try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
			for (int i = 0; i < threads; i++) {
				results.add(pool.submit(() -> {
					start.await();
					return like(fan, postId, true).andReturn().getResponse().getStatus();
				}));
			}
			start.countDown();
			for (Future<Integer> result : results) {
				assertThat(result.get()).isEqualTo(200);
			}
		}
		assertThat(jdbc.sql("SELECT count(*) FROM community_like WHERE post_id = :p").param("p", postId)
			.query(Integer.class).single()).isEqualTo(1);
		assertThat(recorder.of(PostLiked.class).stream().filter(e -> e.postId().value().equals(postId))).hasSize(1);
		like(fan, postId, true).andExpect(status().isOk())
			.andExpect(jsonPath("$.liked").value(true))
			.andExpect(jsonPath("$.likeCount").value(1));
		feed(fan, kr.weathertalk.neighborhood.NeighborhoodTestData.N1, null, null)
			.andExpect(jsonPath("$.items[0].likedByViewer").value(true))
			.andExpect(jsonPath("$.items[0].likeCount").value(1))
			.andExpect(jsonPath("$.items[0].mine").value(false));
	}

	/** BR-COM-10: 본인 게시글에는 좋아요할 수 없다. 없는 게시글은 없음. */
	@Test
	void selfLikeIsRejected() throws Exception {
		clock.set(NOW);
		AuthTestClient.Session author = member();
		UUID postId = newPost(author, "내 글");
		like(author, postId, true).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("SELF_LIKE_NOT_ALLOWED"));
		like(author, UUID.randomUUID(), true).andExpect(status().isNotFound());
		assertThat(count("community_like")).isZero();
	}

	/** legacy 03-#9: 좋아요 해제는 좋아요 레코드만 지운다 — 게시글·회원은 그대로. 해제도 멱등. 다시 누르면 다시 발행. */
	@Test
	void legacy03_9_unlikeRemovesOnlyTheLike() throws Exception {
		clock.set(NOW);
		AuthTestClient.Session author = member();
		AuthTestClient.Session fan = member();
		UUID postId = newPost(author, "글");
		like(fan, postId, true).andExpect(jsonPath("$.likeCount").value(1));
		like(fan, postId, false).andExpect(status().isOk())
			.andExpect(jsonPath("$.liked").value(false))
			.andExpect(jsonPath("$.likeCount").value(0));
		like(fan, postId, false).andExpect(status().isOk()).andExpect(jsonPath("$.likeCount").value(0));

		detail(fan, postId).andExpect(status().isOk()).andExpect(jsonPath("$.post.likedByViewer").value(false));
		assertThat(memberApi.findProfile(fan.memberId()).orElseThrow().withdrawn()).isFalse();
		assertThat(memberApi.findProfile(author.memberId()).orElseThrow().withdrawn()).isFalse();

		like(fan, postId, true).andExpect(jsonPath("$.likeCount").value(1));
		assertThat(events.stream(PostLiked.class).filter(e -> e.postId().value().equals(postId))).hasSize(2);
	}

}
