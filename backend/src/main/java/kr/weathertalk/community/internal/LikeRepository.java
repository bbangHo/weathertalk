package kr.weathertalk.community.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 좋아요 저장소 (community_like). (게시글, 회원) 기본 키로 1건을 보장한다(BR-COM-11, legacy 03-#10).
 * 지울 때는 이 행만 지운다 — 게시글·회원에 아무것도 전파하지 않는다(legacy 03-#9).
 */
@Repository
class LikeRepository {

	private final JdbcClient jdbc;

	LikeRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * 없을 때만 넣는다. 동시에 같은 요청이 와도 하나만 들어가고 나머지는 '이미 좋아요 상태'(0건)가 된다.
	 *
	 * @return 실제로 넣었으면 1
	 */
	int insertIfAbsent(UUID postId, UUID memberId, Instant at) {
		return jdbc.sql("INSERT INTO community_like (post_id, member_id, created_at) VALUES (:p, :m, :at) "
				+ "ON CONFLICT (post_id, member_id) DO NOTHING")
			.param("p", postId)
			.param("m", memberId)
			.param("at", Timestamp.from(at))
			.update();
	}

	int delete(UUID postId, UUID memberId) {
		return jdbc.sql("DELETE FROM community_like WHERE post_id = :p AND member_id = :m")
			.param("p", postId)
			.param("m", memberId)
			.update();
	}

	int deleteByMember(UUID memberId) {
		return jdbc.sql("DELETE FROM community_like WHERE member_id = :m").param("m", memberId).update();
	}

	int count(UUID postId) {
		return jdbc.sql("SELECT count(*) FROM community_like WHERE post_id = :p").param("p", postId).query(Integer.class).single();
	}

}
