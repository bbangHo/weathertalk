package kr.weathertalk.community;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import kr.weathertalk.AuthTestClient;
import kr.weathertalk.feeling.FeelingTestBase;
import kr.weathertalk.member.TemperatureSensitivity;
import kr.weathertalk.neighborhood.DeviceLocation;
import kr.weathertalk.neighborhood.NeighborhoodTestData;
import kr.weathertalk.shared.Ids.NeighborhoodId;
import kr.weathertalk.shared.idempotency.IdempotencyKey;

/**
 * 커뮤니티 통합 테스트 공통: 체감 테스트 초기화(시각·동네·날씨·체감 기록) + 커뮤니티 테이블 비우기, 게시글·댓글·좋아요 요청 도우미.
 * 쓰기 규칙은 실제 API로, 페이징·집계 경계는 행을 직접 넣어(같은 시각을 정확히 맞추려고) 검증한다.
 */
public abstract class CommunityTestBase extends FeelingTestBase {

	@BeforeEach
	void resetCommunity() {
		jdbc.sql("TRUNCATE community_post_image, community_like, community_comment, community_post, "
				+ "community_admin_deletion").update();
	}

	protected AuthTestClient.Session member() throws Exception {
		return signup(TemperatureSensitivity.NORMAL);
	}

	protected static String json(String value) {
		return value == null ? "null" : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
	}

	protected static String ids(List<UUID> ids) {
		return ids.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(",", "[", "]"));
	}

	protected ResultActions createPost(AuthTestClient.Session session, String key, String body, List<UUID> imageIds,
			UUID feelingRecordId, DeviceLocation location) throws Exception {
		String content = "{\"body\":" + json(body) + ",\"imageIds\":" + ids(imageIds) + ",\"feelingRecordId\":"
				+ (feelingRecordId == null ? "null" : "\"" + feelingRecordId + "\"") + "," + locationJson(location) + "}";
		return mvc.perform(auth(post("/api/v1/community/posts"), session).header(IdempotencyKey.HEADER, key)
			.contentType(MediaType.APPLICATION_JSON)
			.content(content));
	}

	protected ResultActions createPost(AuthTestClient.Session session, String body) throws Exception {
		return createPost(session, newKey(), body, List.of(), null, NeighborhoodTestData.INSIDE_N1);
	}

	protected UUID postId(ResultActions result) throws Exception {
		return UUID.fromString(JsonPath.read(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
				"$.postId"));
	}

	protected UUID newPost(AuthTestClient.Session session, String body) throws Exception {
		return postId(createPost(session, body));
	}

	protected ResultActions comment(AuthTestClient.Session session, UUID postId, String body) throws Exception {
		return mvc.perform(auth(post("/api/v1/community/posts/" + postId + "/comments"), session)
			.header(IdempotencyKey.HEADER, newKey())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"body\":" + json(body) + "}"));
	}

	protected ResultActions reply(AuthTestClient.Session session, UUID commentId, String body) throws Exception {
		return mvc.perform(auth(post("/api/v1/community/comments/" + commentId + "/replies"), session)
			.header(IdempotencyKey.HEADER, newKey())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"body\":" + json(body) + "}"));
	}

	protected UUID commentId(ResultActions result) throws Exception {
		return UUID.fromString(JsonPath.read(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
				"$.commentId"));
	}

	protected ResultActions deleteComment(AuthTestClient.Session session, UUID commentId) throws Exception {
		return mvc.perform(auth(delete("/api/v1/community/comments/" + commentId), session));
	}

	protected ResultActions like(AuthTestClient.Session session, UUID postId, boolean liked) throws Exception {
		return mvc.perform(auth(put("/api/v1/community/posts/" + postId + "/like"), session)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"liked\":" + liked + "}"));
	}

	protected ResultActions feed(AuthTestClient.Session session, NeighborhoodId neighborhood, String cursor, Integer size)
			throws Exception {
		MockHttpServletRequestBuilder request = get("/api/v1/community/neighborhoods/" + neighborhood.value() + "/posts");
		if (cursor != null) {
			request = request.param("cursor", cursor);
		}
		if (size != null) {
			request = request.param("size", size.toString());
		}
		return mvc.perform(session == null ? request : auth(request, session));
	}

	protected ResultActions detail(AuthTestClient.Session session, UUID postId) throws Exception {
		MockHttpServletRequestBuilder request = get("/api/v1/community/posts/" + postId);
		return mvc.perform(session == null ? request : auth(request, session));
	}

	protected static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request,
			AuthTestClient.Session session) {
		return request.header(HttpHeaders.AUTHORIZATION, session.bearer());
	}

	protected static String body(ResultActions result) throws Exception {
		return result.andReturn().getResponse().getContentAsString();
	}

	/** 게시글 행을 직접 넣는다(같은 작성 시각·ID 순서를 정확히 맞추는 페이징 경계 테스트용). */
	protected UUID insertPost(UUID authorId, NeighborhoodId neighborhood, Instant createdAt, UUID id) {
		jdbc.sql("INSERT INTO community_post (id, author_id, neighborhood_code, body, created_at) "
				+ "VALUES (:id, :author, :nbr, :body, :at)")
			.param("id", id)
			.param("author", authorId)
			.param("nbr", neighborhood.value())
			.param("body", "글 " + id)
			.param("at", Timestamp.from(createdAt))
			.update();
		return id;
	}

	protected void insertLikes(UUID postId, int count) {
		for (int i = 0; i < count; i++) {
			jdbc.sql("INSERT INTO community_like (post_id, member_id, created_at) VALUES (:p, :m, :at)")
				.param("p", postId)
				.param("m", UUID.randomUUID())
				.param("at", Timestamp.from(clock.instant()))
				.update();
		}
	}

	/** 미첨부 이미지 행을 직접 넣는다(저장소 왕복이 필요 없는 첨부 규칙 테스트용). */
	protected UUID insertUnattachedImage(UUID uploaderId) {
		UUID id = UUID.randomUUID();
		jdbc.sql("INSERT INTO community_post_image (id, uploader_id, status, upload_key, object_key, content_type, "
				+ "size_bytes, width, height, created_at, uploaded_at) VALUES (:id, :u, 'UNATTACHED', :up, :obj, "
				+ "'image/jpeg', 10, 1, 1, :at, :at)")
			.param("id", id)
			.param("u", uploaderId)
			.param("up", "uploads/" + id)
			.param("obj", "images/" + id + ".jpg")
			.param("at", Timestamp.from(clock.instant()))
			.update();
		return id;
	}

	protected String imageStatus(UUID imageId) {
		return jdbc.sql("SELECT status FROM community_post_image WHERE id = :id")
			.param("id", imageId)
			.query(String.class)
			.optional()
			.orElse(null);
	}

	protected int count(String table) {
		return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
	}

}
