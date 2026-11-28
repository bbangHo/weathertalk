package kr.weathertalk.community.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 댓글·답글 저장소 (community_comment, 05 §3.3). */
@Repository
class CommentRepository {

	/**
	 * @param parentId 답글이면 상위 댓글 ID
	 * @param deleted 삭제된 댓글 자리(작성자·본문 없음)
	 */
	record CommentRow(UUID id, UUID postId, UUID parentId, UUID authorId, String body, boolean deleted,
			Instant createdAt, Instant updatedAt) {

		boolean reply() {
			return parentId != null;
		}

	}

	private static final String COLUMNS = "id, post_id, parent_id, author_id, body, status, created_at, updated_at";

	private final JdbcClient jdbc;

	CommentRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	void insert(UUID id, UUID postId, UUID parentId, UUID authorId, String body, Instant createdAt) {
		jdbc.sql("INSERT INTO community_comment (" + COLUMNS + ") VALUES (:id, :post, :parent, :author, :body, 'ACTIVE', "
				+ ":created, NULL)")
			.param("id", id)
			.param("post", postId)
			.param("parent", parentId, java.sql.Types.OTHER)
			.param("author", authorId)
			.param("body", body)
			.param("created", Timestamp.from(createdAt))
			.update();
	}

	Optional<CommentRow> findById(UUID id) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM community_comment WHERE id = :id")
			.param("id", id)
			.query(CommentRepository::map)
			.optional();
	}

	/**
	 * 답글 작성과 댓글 삭제가 같은 댓글에서 겹치지 않게 잠근다 — 답글이 생기는 순간 상위 댓글이 지워지거나,
	 * 마지막 답글 삭제와 새 답글이 엇갈려 빈 자리가 남는 일을 막는다(§3.3 불변식).
	 */
	Optional<CommentRow> lock(UUID id) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM community_comment WHERE id = :id FOR UPDATE")
			.param("id", id)
			.query(CommentRepository::map)
			.optional();
	}

	void updateBody(UUID id, String body, Instant updatedAt) {
		jdbc.sql("UPDATE community_comment SET body = :body, updated_at = :updated WHERE id = :id")
			.param("id", id)
			.param("body", body)
			.param("updated", Timestamp.from(updatedAt))
			.update();
	}

	/** 답글이 남은 댓글을 삭제된 댓글 자리로 바꾼다: 작성자·본문을 지운다. */
	void markDeleted(UUID id) {
		jdbc.sql("UPDATE community_comment SET status = 'DELETED', author_id = NULL, body = NULL, updated_at = NULL "
				+ "WHERE id = :id")
			.param("id", id)
			.update();
	}

	void delete(UUID id) {
		jdbc.sql("DELETE FROM community_comment WHERE id = :id").param("id", id).update();
	}

	int countReplies(UUID parentId) {
		return jdbc.sql("SELECT count(*) FROM community_comment WHERE parent_id = :p")
			.param("p", parentId)
			.query(Integer.class)
			.single();
	}

	/** 게시글의 댓글(답글 제외)을 (작성 시각, ID) 오름차순으로, 커서보다 엄격히 뒤인 것만(FR-COM-10). */
	List<CommentRow> findTopLevelPage(UUID postId, PageCursor cursor, int limit) {
		String sql = "SELECT " + COLUMNS + " FROM community_comment WHERE post_id = :post AND parent_id IS NULL"
				+ (cursor == null ? "" : " AND (created_at, id) > (:cursorAt, :cursorId)")
				+ " ORDER BY created_at, id LIMIT :limit";
		var spec = jdbc.sql(sql).param("post", postId).param("limit", limit);
		if (cursor != null) {
			spec = spec.param("cursorAt", Timestamp.from(cursor.createdAt())).param("cursorId", cursor.id());
		}
		return spec.query(CommentRepository::map).list();
	}

	/** 상위 댓글들의 답글 전부, 오래된순. */
	List<CommentRow> findReplies(Collection<UUID> parentIds) {
		if (parentIds.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("SELECT " + COLUMNS + " FROM community_comment WHERE parent_id IN (:ids) ORDER BY created_at, id")
			.param("ids", List.copyOf(parentIds))
			.query(CommentRepository::map)
			.list();
	}

	/** 탈퇴 익명화: 작성자를 익명 작성자로 바꾼다. */
	void anonymizeAuthor(UUID authorId, UUID anonymous) {
		jdbc.sql("UPDATE community_comment SET author_id = :anon WHERE author_id = :a")
			.param("a", authorId)
			.param("anon", anonymous)
			.update();
	}

	/** 탈퇴 정리 대상: 그 회원의 답글(답글 먼저 지워야 상위 댓글 자리 판정이 맞다). */
	List<UUID> findReplyIdsByAuthor(UUID authorId) {
		return jdbc.sql("SELECT id FROM community_comment WHERE author_id = :a AND parent_id IS NOT NULL")
			.param("a", authorId)
			.query(UUID.class)
			.list();
	}

	List<UUID> findTopLevelIdsByAuthor(UUID authorId) {
		return jdbc.sql("SELECT id FROM community_comment WHERE author_id = :a AND parent_id IS NULL")
			.param("a", authorId)
			.query(UUID.class)
			.list();
	}

	private static CommentRow map(ResultSet rs, int rowNum) throws SQLException {
		Timestamp updated = rs.getTimestamp("updated_at");
		return new CommentRow(rs.getObject("id", UUID.class), rs.getObject("post_id", UUID.class),
				rs.getObject("parent_id", UUID.class), rs.getObject("author_id", UUID.class), rs.getString("body"),
				"DELETED".equals(rs.getString("status")), rs.getTimestamp("created_at").toInstant(),
				updated == null ? null : updated.toInstant());
	}

}
