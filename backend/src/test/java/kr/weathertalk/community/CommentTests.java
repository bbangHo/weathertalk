package kr.weathertalk.community;

import static kr.weathertalk.neighborhood.NeighborhoodTestData.NEAR_N2;
import static kr.weathertalk.weather.WeatherStubs.kst;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;

import com.jayway.jsonpath.JsonPath;

import kr.weathertalk.AuthTestClient;
import kr.weathertalk.IntegrationTest;

/**
 * 댓글·답글 (FR-COM-07~10, BR-COM-02, §3.3 삭제 전이).
 */
@IntegrationTest
class CommentTests extends CommunityTestBase {

	static final Instant NOW = kst("2026-10-13T09:15");

	@Autowired
	ApplicationEvents events;

	/**
	 * BR-COM-02: 다른 동네(관심 동네)의 글에도 위치 확인 없이 댓글·답글을 단다. 댓글작성됨에 게시글 작성자 ID,
	 * 답글이면 상위 댓글 ID·상위 댓글 작성자 ID를 싣는다(contracts §3).
	 */
	@Test
	void commentsAndRepliesOnOtherNeighborhoodPostsPublishCommentCreated() throws Exception {
		clock.set(NOW);
		AuthTestClient.Session author = member();
		AuthTestClient.Session neighbor = member();
		neighborhoodApi.confirmCurrentNeighborhood(neighbor.memberId(), NEAR_N2).orElseThrow();
		UUID postId = newPost(author, "동네 1 글");

		UUID commentId = commentId(comment(neighbor, postId, "관심 동네에서 댓글"));
		UUID replyId = commentId(reply(author, commentId, "작성자 답글"));

		assertThat(events.stream(CommentCreated.class).filter(e -> e.commentId().value().equals(commentId)))
			.singleElement()
			.satisfies(e -> {
				assertThat(e.commenterId()).isEqualTo(neighbor.memberId());
				assertThat(e.postId().value()).isEqualTo(postId);
				assertThat(e.postAuthorId()).isEqualTo(author.memberId());
				assertThat(e.parentCommentId()).isNull();
				assertThat(e.parentCommentAuthorId()).isNull();
			});
		// 본인 글에 단 답글도 발행한다(알림 제외는 notification이 판정)
		assertThat(events.stream(CommentCreated.class).filter(e -> e.commentId().value().equals(replyId)))
			.singleElement()
			.satisfies(e -> {
				assertThat(e.commenterId()).isEqualTo(author.memberId());
				assertThat(e.postAuthorId()).isEqualTo(author.memberId());
				assertThat(e.parentCommentId().value()).isEqualTo(commentId);
				assertThat(e.parentCommentAuthorId()).isEqualTo(neighbor.memberId());
			});
		// M7: notification(댓글 알림)이 구독하므로 발행 저장소에 영속화되고, 처리가 끝나면 모두 완료로 기록된다
		org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc
			.sql("SELECT count(*) FROM event_publication WHERE event_type = :t AND completion_date IS NULL")
			.param("t", CommentCreated.class.getName())
			.query(Integer.class)
			.single()).isZero());

		detail(neighbor, postId).andExpect(status().isOk())
			.andExpect(jsonPath("$.post.commentCount").value(2))
			.andExpect(jsonPath("$.comments.items[0].body").value("관심 동네에서 댓글"))
			.andExpect(jsonPath("$.comments.items[0].mine").value(true))
			.andExpect(jsonPath("$.comments.items[0].replies[0].body").value("작성자 답글"))
			.andExpect(jsonPath("$.comments.items[0].replies[0].mine").value(false));
	}

	/** FR-COM-08: 답글에는 답글을 달 수 없다(깊이 1). 없는 댓글·게시글은 없음. */
	@Test
	void replyToReplyIsRejected() throws Exception {
		clock.set(NOW);
		AuthTestClient.Session author = member();
		UUID postId = newPost(author, "글");
		UUID commentId = commentId(comment(author, postId, "댓글"));
		UUID replyId = commentId(reply(author, commentId, "답글"));
		reply(author, replyId, "답글의 답글").andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("REPLY_NOT_ALLOWED"))
			.andExpect(jsonPath("$.reason").value("REPLY_TO_REPLY"));
		reply(author, UUID.randomUUID(), "없는 댓글").andExpect(status().isNotFound());
		comment(author, UUID.randomUUID(), "없는 글").andExpect(status().isNotFound());
		comment(author, postId, " ").andExpect(status().isBadRequest());
		comment(author, postId, "가".repeat(201)).andExpect(status().isBadRequest());
		comment(author, postId, "가".repeat(200)).andExpect(status().isOk());
	}

	/**
	 * §3.3: 답글이 있는 댓글을 지우면 '삭제된 댓글' 자리(작성자·본문 없음)로 남고, 그 자리에는 답글·수정 불가.
	 * 마지막 답글이 지워지면 자리도 사라진다. 댓글 수에서 자리는 빠진다.
	 */
	@Test
	void deletingCommentWithRepliesLeavesPlaceholderUntilLastReplyIsGone() throws Exception {
		clock.set(NOW);
		AuthTestClient.Session author = member();
		AuthTestClient.Session commenter = member();
		UUID postId = newPost(author, "글");
		UUID commentId = commentId(comment(commenter, postId, "곧 지울 댓글"));
		UUID replyId = commentId(reply(author, commentId, "답글"));

		deleteComment(author, commentId).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_CONTENT_AUTHOR"));
		deleteComment(commenter, commentId).andExpect(status().isNoContent());
		detail(null, postId).andExpect(jsonPath("$.post.commentCount").value(1))
			.andExpect(jsonPath("$.comments.items[0].deleted").value(true))
			.andExpect(jsonPath("$.comments.items[0].body").doesNotExist())
			.andExpect(jsonPath("$.comments.items[0].author").doesNotExist())
			.andExpect(jsonPath("$.comments.items[0].replies[0].body").value("답글"));
		assertThat(jdbc.sql("SELECT author_id IS NULL AND body IS NULL FROM community_comment WHERE id = :id")
			.param("id", commentId)
			.query(Boolean.class)
			.single()).isTrue();

		reply(author, commentId, "자리에 답글").andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.reason").value("PARENT_DELETED"));
		mvc.perform(auth(patch("/api/v1/community/comments/" + commentId), commenter)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"body\":\"되살리기\"}")).andExpect(status().isNotFound());
		deleteComment(commenter, commentId).andExpect(status().isNoContent());

		deleteComment(author, replyId).andExpect(status().isNoContent());
		assertThat(count("community_comment")).isZero();
		detail(null, postId).andExpect(jsonPath("$.comments.items.length()").value(0))
			.andExpect(jsonPath("$.post.commentCount").value(0));
		deleteComment(author, replyId).andExpect(status().isNoContent());
	}

	/** FR-COM-09: 본문만 수정, 작성자만, "수정됨" 표시. 답글이 없는 댓글 삭제는 바로 지운다. */
	@Test
	void editIsAuthorOnlyAndMarksEdited() throws Exception {
		clock.set(NOW);
		AuthTestClient.Session author = member();
		AuthTestClient.Session other = member();
		UUID postId = newPost(author, "글");
		UUID commentId = commentId(comment(author, postId, "처음"));
		mvc.perform(auth(patch("/api/v1/community/comments/" + commentId), other).contentType(MediaType.APPLICATION_JSON)
			.content("{\"body\":\"남이 고침\"}")).andExpect(status().isForbidden());
		mvc.perform(auth(patch("/api/v1/community/comments/" + commentId), author).contentType(MediaType.APPLICATION_JSON)
			.content("{\"body\":\"고침\"}")).andExpect(status().isNoContent());
		detail(other, postId).andExpect(jsonPath("$.comments.items[0].body").value("고침"))
			.andExpect(jsonPath("$.comments.items[0].edited").value(true));
		deleteComment(author, commentId).andExpect(status().isNoContent());
		assertThat(count("community_comment")).isZero();
	}

	/** FR-COM-10: 댓글은 오래된순 커서 페이징(community.comment.page-size), 같은 시각은 ID순. 답글은 상위 댓글 아래 전부. */
	@Test
	void commentsArePagedOldestFirst() throws Exception {
		clock.set(NOW);
		AuthTestClient.Session author = member();
		UUID postId = newPost(author, "댓글 많은 글");
		for (int i = 0; i < 31; i++) {
			jdbc.sql("INSERT INTO community_comment (id, post_id, author_id, body, status, created_at) "
					+ "VALUES (:id, :post, :author, :body, 'ACTIVE', :at)")
				.param("id", UUID.randomUUID())
				.param("post", postId)
				.param("author", author.memberId().value())
				.param("body", "댓글 " + i)
				.param("at", Timestamp.from(NOW))
				.update();
		}
		String first = body(detail(null, postId).andExpect(status().isOk()));
		List<String> firstIds = JsonPath.read(first, "$.comments.items[*].commentId");
		assertThat(firstIds).hasSize(30).isSorted();
		String cursor = JsonPath.read(first, "$.comments.nextCursor");
		String second = body(mvc.perform(get("/api/v1/community/posts/" + postId + "/comments").param("cursor", cursor))
			.andExpect(status().isOk()));
		List<String> secondIds = JsonPath.read(second, "$.items[*].commentId");
		assertThat(secondIds).hasSize(1);
		assertThat(secondIds.getFirst()).isGreaterThan(firstIds.getLast());
		assertThat(JsonPath.<Object>read(second, "$.nextCursor")).isNull();
	}

}
