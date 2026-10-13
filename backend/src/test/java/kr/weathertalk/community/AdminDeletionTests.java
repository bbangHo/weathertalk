package kr.weathertalk.community;

import static kr.weathertalk.neighborhood.NeighborhoodTestData.N1;
import static kr.weathertalk.weather.WeatherStubs.kst;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import kr.weathertalk.AuthTestClient;
import kr.weathertalk.IntegrationTest;

/**
 * 관리자 삭제 (FR-COM-15, BR-COM-17, ADR 0012). 관리자 여부는 요청자 해석의 관리자 여부로만 판정한다.
 */
@IntegrationTest
class AdminDeletionTests extends CommunityTestBase {

	static final Instant NOW = kst("2026-10-13T09:15");

	private ResultActions adminDelete(AuthTestClient.Session session, String type, UUID target, String reason)
			throws Exception {
		var request = post("/api/v1/admin/community/deletions").contentType(MediaType.APPLICATION_JSON)
			.content("{\"targetType\":\"" + type + "\",\"targetId\":\"" + target + "\",\"reason\":" + json(reason) + "}");
		return mvc.perform(session == null ? request : auth(request, session));
	}

	private AuthTestClient.Session admin() throws Exception {
		AuthTestClient.Session session = member();
		jdbc.sql("UPDATE member_member SET role = 'ADMIN' WHERE id = :id").param("id", session.memberId().value()).update();
		return session;
	}

	/** 관리자 외 거부: 작성자 본인이어도, 다른 회원이어도 이 명령은 쓸 수 없다. 비회원은 가입 필요. */
	@Test
	void nonAdminsAreRejectedEvenForTheirOwnPosts() throws Exception {
		clock.set(NOW);
		AuthTestClient.Session author = member();
		AuthTestClient.Session other = member();
		UUID postId = newPost(author, "내 글");
		UUID commentId = commentId(comment(other, postId, "댓글"));

		adminDelete(author, "POST", postId, "본인").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ADMIN_REQUIRED"));
		adminDelete(other, "POST", postId, "남의 글").andExpect(status().isForbidden());
		adminDelete(other, "COMMENT", commentId, "내 댓글").andExpect(status().isForbidden());
		adminDelete(null, "POST", postId, "비회원").andExpect(status().isUnauthorized());
		assertThat(count("community_post")).isEqualTo(1);
		assertThat(count("community_comment")).isEqualTo(1);
		assertThat(count("community_admin_deletion")).isZero();
	}

	/** 게시글: 작성자 삭제와 같은 규칙(댓글·좋아요 함께, 이미지 정리 대상) + 삭제 주체·사유 기록(본문·이미지 미보관). */
	@Test
	void adminDeletesPostAndRecordsWhoAndWhy() throws Exception {
		clock.set(NOW);
		AuthTestClient.Session author = member();
		AuthTestClient.Session admin = admin();
		UUID image = insertUnattachedImage(author.memberId().value());
		UUID postId = postId(createPost(author, newKey(), "부적절한 글", List.of(image), null,
				kr.weathertalk.neighborhood.NeighborhoodTestData.INSIDE_N1));
		comment(admin, postId, "관리자도 댓글은 단다").andExpect(status().isOk());

		adminDelete(admin, "POST", postId, " ").andExpect(status().isBadRequest());
		adminDelete(admin, "POST", postId, "가".repeat(201)).andExpect(status().isBadRequest());
		adminDelete(admin, "POST", postId, "불법 촬영물").andExpect(status().isNoContent());

		assertThat(count("community_post")).isZero();
		assertThat(count("community_comment")).isZero();
		assertThat(imageStatus(image)).isEqualTo("DISPOSABLE");
		Map<String, Object> row = jdbc.sql("SELECT * FROM community_admin_deletion").query().singleRow();
		assertThat(row.get("target_type")).isEqualTo("POST");
		assertThat(row.get("target_id")).isEqualTo(postId);
		assertThat(row.get("target_author_id")).isEqualTo(author.memberId().value());
		assertThat(row.get("neighborhood_code")).isEqualTo(N1.value());
		assertThat(row.get("admin_id")).isEqualTo(admin.memberId().value());
		assertThat(row.get("reason")).isEqualTo("불법 촬영물");
		assertThat(row.keySet()).noneMatch(column -> column.contains("body") || column.contains("image"));

		// 이미 없는 대상: 기록 없이 성공(멱등)
		adminDelete(admin, "POST", postId, "다시").andExpect(status().isNoContent());
		adminDelete(admin, "COMMENT", UUID.randomUUID(), "없는 댓글").andExpect(status().isNoContent());
		assertThat(count("community_admin_deletion")).isEqualTo(1);
	}

	/** 댓글: 답글이 남은 댓글은 삭제된 댓글 자리로(작성자 삭제와 같은 규칙), 기록에는 그 게시글의 동네. */
	@Test
	void adminDeletesCommentWithSameRules() throws Exception {
		clock.set(NOW);
		AuthTestClient.Session author = member();
		AuthTestClient.Session troll = member();
		AuthTestClient.Session admin = admin();
		UUID postId = newPost(author, "글");
		UUID bad = commentId(comment(troll, postId, "나쁜 댓글"));
		commentId(reply(author, bad, "답글"));

		adminDelete(admin, "COMMENT", bad, "욕설").andExpect(status().isNoContent());
		detail(null, postId).andExpect(jsonPath("$.comments.items[0].deleted").value(true))
			.andExpect(jsonPath("$.comments.items[0].replies.length()").value(1));
		Map<String, Object> row = jdbc.sql("SELECT * FROM community_admin_deletion").query().singleRow();
		assertThat(row.get("target_type")).isEqualTo("COMMENT");
		assertThat(row.get("target_author_id")).isEqualTo(troll.memberId().value());
		assertThat(row.get("neighborhood_code")).isEqualTo(N1.value());
		// 자리만 남은 댓글은 이미 지워진 것으로 본다
		adminDelete(admin, "COMMENT", bad, "다시").andExpect(status().isNoContent());
		assertThat(count("community_admin_deletion")).isEqualTo(1);
		mvc.perform(auth(post("/api/v1/admin/community/deletions"), admin).contentType(MediaType.APPLICATION_JSON)
			.content("{\"targetType\":\"USER\",\"targetId\":\"" + postId + "\",\"reason\":\"x\"}"))
			.andExpect(status().isBadRequest());
	}

}
