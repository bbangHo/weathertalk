package kr.weathertalk.community.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 게시글 이미지 기록 (community_post_image, 05 §3.2). 파일은 객체 저장소에 있고 여기에는 키·상태만 둔다. */
@Repository
class PostImageRepository {

	enum Status {

		/** 사전 서명 주소를 받았고 업로드 확인(메타데이터 제거) 전 */
		UPLOADING,

		/** 미첨부 */
		UNATTACHED,

		ATTACHED,

		/** 정리 대상: 정리 배치가 파일을 지운 뒤 행을 지운다 */
		DISPOSABLE

	}

	record ImageRow(UUID id, UUID uploaderId, UUID postId, Integer position, Status status, String uploadKey,
			String objectKey, String contentType, Long sizeBytes, Integer width, Integer height, Instant createdAt,
			Instant uploadedAt) {
	}

	private static final String COLUMNS = "id, uploader_id, post_id, position, status, upload_key, object_key, "
			+ "content_type, size_bytes, width, height, created_at, uploaded_at";

	private final JdbcClient jdbc;

	PostImageRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	void insertUploading(UUID id, UUID uploaderId, String uploadKey, Instant createdAt) {
		jdbc.sql("INSERT INTO community_post_image (id, uploader_id, status, upload_key, created_at) "
				+ "VALUES (:id, :uploader, 'UPLOADING', :key, :created)")
			.param("id", id)
			.param("uploader", uploaderId)
			.param("key", uploadKey)
			.param("created", Timestamp.from(createdAt))
			.update();
	}

	Optional<ImageRow> findById(UUID id) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM community_post_image WHERE id = :id")
			.param("id", id)
			.query(PostImageRepository::map)
			.optional();
	}

	/** 업로드 확인 결과 반영. 정리 배치가 먼저 정리 대상으로 돌렸으면(만료) 0건이다. */
	int markUploaded(UUID id, String objectKey, String contentType, long sizeBytes, int width, int height,
			Instant uploadedAt) {
		return jdbc.sql("UPDATE community_post_image SET status = 'UNATTACHED', object_key = :key, content_type = :type, "
				+ "size_bytes = :size, width = :w, height = :h, uploaded_at = :at WHERE id = :id AND status = 'UPLOADING'")
			.param("id", id)
			.param("key", objectKey)
			.param("type", contentType)
			.param("size", sizeBytes)
			.param("w", width)
			.param("h", height)
			.param("at", Timestamp.from(uploadedAt))
			.update();
	}

	void deleteRow(UUID id) {
		jdbc.sql("DELETE FROM community_post_image WHERE id = :id").param("id", id).update();
	}

	/** 첨부 검증 동안 정리 배치·다른 게시글 작성과 겹치지 않게 잠근다. */
	List<ImageRow> lockAll(Collection<UUID> ids) {
		if (ids.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("SELECT " + COLUMNS + " FROM community_post_image WHERE id IN (:ids) ORDER BY id FOR UPDATE")
			.param("ids", List.copyOf(ids))
			.query(PostImageRepository::map)
			.list();
	}

	void attach(UUID id, UUID postId, int position) {
		jdbc.sql("UPDATE community_post_image SET status = 'ATTACHED', post_id = :post, position = :pos WHERE id = :id")
			.param("id", id)
			.param("post", postId)
			.param("pos", position)
			.update();
	}

	void dispose(Collection<UUID> ids, Instant now) {
		if (ids.isEmpty()) {
			return;
		}
		jdbc.sql("UPDATE community_post_image SET status = 'DISPOSABLE', post_id = NULL, position = NULL, disposed_at = :now "
				+ "WHERE id IN (:ids)")
			.param("ids", List.copyOf(ids))
			.param("now", Timestamp.from(now))
			.update();
	}

	/** 게시글 삭제 시 그 이미지를 정리 대상으로 돌린다(BR-COM-09). 파일 삭제는 정리 배치가 한다. */
	void disposeByPost(UUID postId, Instant now) {
		jdbc.sql("UPDATE community_post_image SET status = 'DISPOSABLE', post_id = NULL, position = NULL, disposed_at = :now "
				+ "WHERE post_id = :post")
			.param("post", postId)
			.param("now", Timestamp.from(now))
			.update();
	}

	/** 탈퇴 정리: 그 회원이 올린 미첨부·업로드 중 이미지(FR-COM-13 규칙 4). */
	void disposeUnattachedByUploader(UUID uploaderId, Instant now) {
		jdbc.sql("UPDATE community_post_image SET status = 'DISPOSABLE', disposed_at = :now "
				+ "WHERE uploader_id = :u AND status IN ('UPLOADING', 'UNATTACHED')")
			.param("u", uploaderId)
			.param("now", Timestamp.from(now))
			.update();
	}

	/** 게시글별 첨부 이미지, 순서대로. */
	Map<UUID, List<ImageRow>> findAttached(Collection<UUID> postIds) {
		Map<UUID, List<ImageRow>> result = new LinkedHashMap<>();
		if (postIds.isEmpty()) {
			return result;
		}
		jdbc.sql("SELECT " + COLUMNS + " FROM community_post_image WHERE post_id IN (:ids) AND status = 'ATTACHED' "
				+ "ORDER BY post_id, position")
			.param("ids", List.copyOf(postIds))
			.query(PostImageRepository::map)
			.list()
			.forEach(row -> result.computeIfAbsent(row.postId(), k -> new java.util.ArrayList<>()).add(row));
		return result;
	}

	/**
	 * 보관 기간을 넘긴 미첨부·업로드 중 이미지를 정리 대상으로 돌린다(FR-COM-14). 파일을 지우기 전에 상태부터 바꿔,
	 * 지우는 도중에 게시글에 첨부되는 일이 없게 한다(첨부는 미첨부 상태만 받는다).
	 */
	int disposeExpired(Instant cutoff, Instant now) {
		return jdbc.sql("UPDATE community_post_image SET status = 'DISPOSABLE', disposed_at = :now "
				+ "WHERE (status = 'UNATTACHED' AND uploaded_at < :cutoff) OR (status = 'UPLOADING' AND created_at < :cutoff)")
			.param("cutoff", Timestamp.from(cutoff))
			.param("now", Timestamp.from(now))
			.update();
	}

	List<ImageRow> findDisposable(int limit) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM community_post_image WHERE status = 'DISPOSABLE' "
				+ "ORDER BY disposed_at, id LIMIT :limit")
			.param("limit", limit)
			.query(PostImageRepository::map)
			.list();
	}

	void deleteDisposed(UUID id) {
		jdbc.sql("DELETE FROM community_post_image WHERE id = :id AND status = 'DISPOSABLE'").param("id", id).update();
	}

	private static ImageRow map(ResultSet rs, int rowNum) throws SQLException {
		Timestamp uploaded = rs.getTimestamp("uploaded_at");
		return new ImageRow(rs.getObject("id", UUID.class), rs.getObject("uploader_id", UUID.class),
				rs.getObject("post_id", UUID.class), (Integer) rs.getObject("position", Integer.class),
				Status.valueOf(rs.getString("status")), rs.getString("upload_key"), rs.getString("object_key"),
				rs.getString("content_type"), rs.getObject("size_bytes", Long.class),
				rs.getObject("width", Integer.class), rs.getObject("height", Integer.class),
				rs.getTimestamp("created_at").toInstant(), uploaded == null ? null : uploaded.toInstant());
	}

}
