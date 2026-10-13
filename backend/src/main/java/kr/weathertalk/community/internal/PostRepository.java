package kr.weathertalk.community.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 게시글 저장소 (community_post) + 좋아요·댓글 수 집계. 좋아요 수·댓글 수는 따로 들고 있지 않고 조회 때 센다 —
 * 삭제·탈퇴 정리 경로가 여럿이라 비정규화한 수가 어긋날 여지를 없앤다.
 */
@Repository
class PostRepository {

	/** @param updatedAt 수정된 적이 없으면 null */
	record PostRow(UUID id, UUID authorId, String neighborhoodCode, String body, UUID feelingRecordId,
			Instant createdAt, Instant updatedAt) {
	}

	private static final String COLUMNS = "id, author_id, neighborhood_code, body, feeling_record_id, created_at, updated_at";

	private final JdbcClient jdbc;

	PostRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	void insert(PostRow post) {
		jdbc.sql("INSERT INTO community_post (" + COLUMNS + ") VALUES (:id, :author, :nbr, :body, :feeling, :created, NULL)")
			.param("id", post.id())
			.param("author", post.authorId())
			.param("nbr", post.neighborhoodCode())
			.param("body", post.body())
			.param("feeling", post.feelingRecordId(), java.sql.Types.OTHER)
			.param("created", Timestamp.from(post.createdAt()))
			.update();
	}

	Optional<PostRow> findById(UUID id) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM community_post WHERE id = :id")
			.param("id", id)
			.query(PostRepository::map)
			.optional();
	}

	/** 수정·삭제·관리자 삭제가 같은 게시글에서 겹치지 않게 행을 잠근다. */
	Optional<PostRow> lock(UUID id) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM community_post WHERE id = :id FOR UPDATE")
			.param("id", id)
			.query(PostRepository::map)
			.optional();
	}

	void updateContent(UUID id, String body, boolean detachFeelingRecord, Instant updatedAt) {
		jdbc.sql("UPDATE community_post SET body = :body, updated_at = :updated"
				+ (detachFeelingRecord ? ", feeling_record_id = NULL" : "") + " WHERE id = :id")
			.param("id", id)
			.param("body", body)
			.param("updated", Timestamp.from(updatedAt))
			.update();
	}

	/** 댓글·답글·좋아요는 외래 키로 함께 지워진다(BR-COM-09). 이미지는 호출 쪽이 먼저 정리 대상으로 돌린다. */
	void delete(UUID id) {
		jdbc.sql("DELETE FROM community_post WHERE id = :id").param("id", id).update();
	}

	/** 피드: 동네의 게시글을 (작성 시각, ID) 내림차순으로, 커서보다 엄격히 작은 것만(BR-COM-08). */
	List<PostRow> findNeighborhoodPage(String neighborhoodCode, PageCursor cursor, int limit) {
		return page("neighborhood_code = :key", neighborhoodCode, cursor, limit);
	}

	/** 내가 쓴 글: 작성자의 게시글을 피드와 같은 정렬·커서로(FR-COM-16). */
	List<PostRow> findAuthorPage(UUID authorId, PageCursor cursor, int limit) {
		return page("author_id = :key", authorId, cursor, limit);
	}

	private List<PostRow> page(String condition, Object key, PageCursor cursor, int limit) {
		String sql = "SELECT " + COLUMNS + " FROM community_post WHERE " + condition
				+ (cursor == null ? "" : " AND (created_at, id) < (:cursorAt, :cursorId)")
				+ " ORDER BY created_at DESC, id DESC LIMIT :limit";
		var spec = jdbc.sql(sql).param("key", key).param("limit", limit);
		if (cursor != null) {
			spec = spec.param("cursorAt", Timestamp.from(cursor.createdAt())).param("cursorId", cursor.id());
		}
		return spec.query(PostRepository::map).list();
	}

	/**
	 * 인기글 후보 (BR-COM-13): 동네에서 since 이후 작성되고 좋아요가 minLikes 이상인 글을 좋아요 수 내림차순, 같으면 최신순.
	 * 개수는 자르지 않는다 — 탈퇴 작성자 글을 걸러낸 뒤 호출 쪽이 자른다.
	 */
	List<PostRow> findPopularCandidates(String neighborhoodCode, Instant since, int minLikes) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM (SELECT p.*, (SELECT count(*) FROM community_like l WHERE l.post_id = p.id) "
				+ "AS likes FROM community_post p WHERE p.neighborhood_code = :nbr AND p.created_at >= :since) c "
				+ "WHERE likes >= :min ORDER BY likes DESC, created_at DESC, id DESC")
			.param("nbr", neighborhoodCode)
			.param("since", Timestamp.from(since))
			.param("min", minLikes)
			.query(PostRepository::map)
			.list();
	}

	List<PostRow> findByAuthor(UUID authorId) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM community_post WHERE author_id = :a")
			.param("a", authorId)
			.query(PostRepository::map)
			.list();
	}

	Map<UUID, Integer> likeCounts(Collection<UUID> postIds) {
		return counts("SELECT post_id, count(*) AS n FROM community_like WHERE post_id IN (:ids) GROUP BY post_id",
				postIds);
	}

	/** 댓글 수 = 댓글+답글 중 삭제된 댓글 자리를 뺀 수(§3.1). */
	Map<UUID, Integer> commentCounts(Collection<UUID> postIds) {
		return counts("SELECT post_id, count(*) AS n FROM community_comment WHERE post_id IN (:ids) AND status = 'ACTIVE' "
				+ "GROUP BY post_id", postIds);
	}

	Set<UUID> likedBy(UUID memberId, Collection<UUID> postIds) {
		if (postIds.isEmpty()) {
			return Set.of();
		}
		return new HashSet<>(jdbc.sql("SELECT post_id FROM community_like WHERE member_id = :m AND post_id IN (:ids)")
			.param("m", memberId)
			.param("ids", List.copyOf(postIds))
			.query((rs, n) -> rs.getObject("post_id", UUID.class))
			.list());
	}

	private Map<UUID, Integer> counts(String sql, Collection<UUID> postIds) {
		Map<UUID, Integer> result = new HashMap<>();
		if (postIds.isEmpty()) {
			return result;
		}
		jdbc.sql(sql)
			.param("ids", List.copyOf(postIds))
			.query((rs, n) -> Map.entry(rs.getObject("post_id", UUID.class), rs.getInt("n")))
			.list()
			.forEach(e -> result.put(e.getKey(), e.getValue()));
		return result;
	}

	private static PostRow map(ResultSet rs, int rowNum) throws SQLException {
		Timestamp updated = rs.getTimestamp("updated_at");
		return new PostRow(rs.getObject("id", UUID.class), rs.getObject("author_id", UUID.class),
				rs.getString("neighborhood_code"), rs.getString("body"), rs.getObject("feeling_record_id", UUID.class),
				rs.getTimestamp("created_at").toInstant(), updated == null ? null : updated.toInstant());
	}

}
