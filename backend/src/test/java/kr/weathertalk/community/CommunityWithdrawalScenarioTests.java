package kr.weathertalk.community;

import static kr.weathertalk.neighborhood.NeighborhoodTestData.INSIDE_N1;
import static kr.weathertalk.weather.WeatherStubs.kst;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.test.Scenario;

import kr.weathertalk.AuthTestClient;
import kr.weathertalk.IntegrationTest;
import kr.weathertalk.member.MemberWithdrawn;

/**
 * 탈퇴 → 회원탈퇴함 → 커뮤니티 정리 (FR-COM-13, BR-COM-14, ADR 0010, legacy 03-#23).
 * 그 회원의 게시글(남의 댓글·좋아요 포함)·댓글·답글·좋아요를 지우고, 답글이 남은 댓글은 삭제된 댓글 자리로, 이미지는 정리 대상으로 돌린다.
 */
@IntegrationTest
class CommunityWithdrawalScenarioTests extends CommunityTestBase {

	static final Instant NOW = kst("2026-10-13T09:15");

	@Test
	void legacy03_23_withdrawalCleansUpPostsCommentsLikesAndImages(Scenario scenario) throws Exception {
		clock.set(NOW);
		AuthTestClient.Session leaving = member();
		AuthTestClient.Session staying = member();
		UUID leavingId = leaving.memberId().value();

		UUID attached = insertUnattachedImage(leavingId);
		UUID unattached = insertUnattachedImage(leavingId);
		UUID leavingPost = postId(createPost(leaving, newKey(), "떠날 회원의 글", List.of(attached), null, INSIDE_N1));
		commentId(comment(staying, leavingPost, "남는 회원의 댓글"));
		like(staying, leavingPost, true).andExpect(status().isOk());

		UUID stayingPost = newPost(staying, "남는 회원의 글");
		UUID withReplies = commentId(comment(leaving, stayingPost, "답글 달린 댓글"));
		commentId(reply(staying, withReplies, "남는 회원의 답글"));
		clock.advance(Duration.ofSeconds(1));
		UUID onlyOwnReplies = commentId(comment(leaving, stayingPost, "자기 답글만 달린 댓글"));
		commentId(reply(leaving, onlyOwnReplies, "자기 답글"));
		clock.advance(Duration.ofSeconds(1));
		commentId(comment(leaving, stayingPost, "답글 없는 댓글"));
		clock.advance(Duration.ofSeconds(1));
		UUID stayingComment = commentId(comment(staying, stayingPost, "남는 회원의 댓글"));
		commentId(reply(leaving, stayingComment, "떠날 회원의 답글"));
		like(leaving, stayingPost, true).andExpect(status().isOk());

		scenario.stimulate(() -> withdraw(leaving))
			.andWaitAtMost(Duration.ofSeconds(10))
			.andWaitForStateChange(() -> remainingOf(leavingId), remaining -> remaining == 0)
			.andVerify(remaining -> {
				assertThat(jdbc.sql("SELECT count(*) FROM community_post WHERE id = :id").param("id", leavingPost)
					.query(Integer.class).single()).isZero();
				assertThat(imageStatus(attached)).isEqualTo("DISPOSABLE");
				assertThat(imageStatus(unattached)).isEqualTo("DISPOSABLE");
				assertThat(jdbc.sql("SELECT count(*) FROM community_like WHERE post_id = :p").param("p", stayingPost)
					.query(Integer.class).single()).isZero();
			});

		// 남는 회원의 글: 답글 달린 댓글만 '삭제된 댓글' 자리로 남고, 나머지 떠난 회원의 댓글·답글은 사라진다
		detail(staying, stayingPost).andExpect(status().isOk())
			.andExpect(jsonPath("$.post.likeCount").value(0))
			.andExpect(jsonPath("$.post.commentCount").value(2))
			.andExpect(jsonPath("$.comments.items.length()").value(2))
			.andExpect(jsonPath("$.comments.items[0].commentId").value(withReplies.toString()))
			.andExpect(jsonPath("$.comments.items[0].deleted").value(true))
			.andExpect(jsonPath("$.comments.items[0].replies[0].body").value("남는 회원의 답글"))
			.andExpect(jsonPath("$.comments.items[1].commentId").value(stayingComment.toString()))
			.andExpect(jsonPath("$.comments.items[1].replies.length()").value(0));

		// 이벤트는 구독자별로 완료 처리된다(실패 시 발행 저장소에서 재처리)
		org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc
			.sql("SELECT count(*) FROM event_publication WHERE event_type = :t AND serialized_event LIKE :m "
					+ "AND completion_date IS NULL")
			.param("t", MemberWithdrawn.class.getName())
			.param("m", "%" + leavingId + "%")
			.query(Integer.class)
			.single()).isZero());
	}

	/**
	 * 카카오 연결 해제 웹훅으로 탈퇴하면 글·댓글·좋아요를 지우지 않고 익명으로 남긴다(사용자 결정 2026-11-28). 작성자는 "탈퇴한 이웃",
	 * 좋아요 수는 그대로, 글에 붙은 사진도 그대로. 남은 회원은 그 글에 댓글을, 다른 회원은 좋아요를 계속 할 수 있다.
	 */
	@Test
	void kakaoUnlinkWithdrawalKeepsPostsCommentsLikesAnonymously() throws Exception {
		clock.set(NOW);
		AuthTestClient.Session leaving = member();
		AuthTestClient.Session leaving2 = member();
		AuthTestClient.Session staying = member();
		UUID leavingId = leaving.memberId().value();

		UUID attached = insertUnattachedImage(leavingId);
		UUID unattached = insertUnattachedImage(leavingId);
		UUID leavingPost = postId(createPost(leaving, newKey(), "떠날 회원의 글", List.of(attached), null, INSIDE_N1));
		like(staying, leavingPost, true).andExpect(status().isOk());
		UUID stayingPost = newPost(staying, "남는 회원의 글");
		UUID leavingComment = commentId(comment(leaving, stayingPost, "떠날 회원의 댓글"));
		commentId(reply(leaving, leavingComment, "떠날 회원의 답글"));
		like(leaving, stayingPost, true).andExpect(status().isOk());
		like(leaving2, stayingPost, true).andExpect(status().isOk());

		long kakaoId = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L);
		kakaoWebhookWithdraw(leaving, kakaoId);
		kakaoWebhookWithdraw(leaving2, kakaoId + 1);
		org.awaitility.Awaitility.await()
			.atMost(Duration.ofSeconds(10))
			.untilAsserted(() -> assertThat(remainingOf(leavingId) + remainingOf(leaving2.memberId().value())).isZero());

		assertThat(imageStatus(attached)).isEqualTo("ATTACHED");
		assertThat(imageStatus(unattached)).isEqualTo("DISPOSABLE");
		detail(staying, leavingPost).andExpect(status().isOk())
			.andExpect(jsonPath("$.post.body").value("떠날 회원의 글"))
			.andExpect(jsonPath("$.post.author.nickname").value("탈퇴한 이웃"))
			.andExpect(jsonPath("$.post.author.temperatureSensitivity").doesNotExist())
			.andExpect(jsonPath("$.post.mine").value(false))
			.andExpect(jsonPath("$.post.likedByViewer").value(true))
			.andExpect(jsonPath("$.post.likeCount").value(1))
			.andExpect(jsonPath("$.post.images.length()").value(1));
		detail(staying, stayingPost).andExpect(status().isOk())
			.andExpect(jsonPath("$.post.likeCount").value(2))
			.andExpect(jsonPath("$.post.likedByViewer").value(false))
			.andExpect(jsonPath("$.comments.items[0].deleted").value(false))
			.andExpect(jsonPath("$.comments.items[0].body").value("떠날 회원의 댓글"))
			.andExpect(jsonPath("$.comments.items[0].author.nickname").value("탈퇴한 이웃"))
			.andExpect(jsonPath("$.comments.items[0].mine").value(false))
			.andExpect(jsonPath("$.comments.items[0].replies[0].body").value("떠날 회원의 답글"));

		// 익명 글에 댓글을 달 수 있고, 익명 좋아요가 있는 글에 새 좋아요도 붙는다
		commentId(comment(staying, leavingPost, "남는 회원의 댓글"));
		like(member(), stayingPost, true).andExpect(status().isOk());
		detail(staying, stayingPost).andExpect(jsonPath("$.post.likeCount").value(3));
	}

	/** DEV 로그인 계정을 카카오 계정으로 바꿔 유일한 수단으로 만들고, 카카오 연결 해제 웹훅을 보낸다. */
	private void kakaoWebhookWithdraw(AuthTestClient.Session session, long kakaoId) throws Exception {
		jdbc.sql("UPDATE member_login_account SET provider = 'KAKAO', provider_user_id = :k WHERE member_id = :m")
			.param("k", Long.toString(kakaoId))
			.param("m", session.memberId().value())
			.update();
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/webhooks/kakao/unlink")
			.header(org.springframework.http.HttpHeaders.AUTHORIZATION,
					"KakaoAK " + kr.weathertalk.member.provider.ProviderTestKeys.KAKAO_ADMIN_KEY)
			.param("app_id", kr.weathertalk.member.provider.ProviderTestKeys.KAKAO_APP_ID)
			.param("user_id", Long.toString(kakaoId))
			.param("referrer_type", "UNLINK_FROM_APPS")).andExpect(status().isOk());
	}

	/** 게시글·댓글·답글·좋아요 중 떠난 회원 것이 남은 수. */
	private int remainingOf(UUID memberId) {
		return jdbc.sql("SELECT (SELECT count(*) FROM community_post WHERE author_id = :m) "
				+ "+ (SELECT count(*) FROM community_comment WHERE author_id = :m) "
				+ "+ (SELECT count(*) FROM community_like WHERE member_id = :m) "
				+ "+ (SELECT count(*) FROM community_post_image WHERE uploader_id = :m AND status <> 'DISPOSABLE')")
			.param("m", memberId)
			.query(Integer.class)
			.single();
	}

	private void withdraw(AuthTestClient.Session session) {
		try {
			mvc.perform(auth(delete("/api/v1/members/me"), session)).andExpect(status().isNoContent());
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

}
