package kr.weathertalk.community.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.weathertalk.community.internal.CommentRepository.CommentRow;
import kr.weathertalk.community.internal.PostRepository.PostRow;
import kr.weathertalk.community.internal.config.CommunityProperties;
import kr.weathertalk.member.Requester;
import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;

/**
 * 관리자 삭제 (FR-COM-15, BR-COM-17, ADR 0012). 관리자 여부는 `현재 요청자 해석`의 관리자 여부로만 판정한다(contracts.md §4) —
 * 작성자 본인이어도 관리자가 아니면 이 명령은 쓸 수 없다. 삭제 규칙은 작성자 삭제와 같고, 같은 트랜잭션에서 삭제 주체·사유를 기록한다.
 * 지운 본문·이미지는 기록에 남기지 않는다. 이벤트·작성자 통지 없음.
 */
@Service
public class AdminDeletionService {

	public enum TargetType {

		POST, COMMENT

	}

	private final PostRepository posts;
	private final CommentRepository comments;
	private final PostService postService;
	private final CommentService commentService;
	private final JdbcClient jdbc;
	private final CommunityProperties properties;
	private final Clock clock;

	AdminDeletionService(PostRepository posts, CommentRepository comments, PostService postService,
			CommentService commentService, JdbcClient jdbc, CommunityProperties properties, Clock clock) {
		this.posts = posts;
		this.comments = comments;
		this.postService = postService;
		this.commentService = commentService;
		this.jdbc = jdbc;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * @return 지웠으면 true. 이미 없는 대상이면 기록 없이 false(멱등 — 오류 아님)
	 * @throws DomainException ADMIN_REQUIRED — 관리자 아님 / INVALID_REQUEST — 대상 종류·ID 없음, 사유 없음·길이 초과
	 */
	@Transactional
	public boolean delete(Requester.Member requester, TargetType type, UUID targetId, String reason) {
		if (!requester.admin()) {
			throw new DomainException(ErrorCode.ADMIN_REQUIRED, "관리자만 쓸 수 있는 명령입니다.");
		}
		if (type == null || targetId == null) {
			throw new DomainException(ErrorCode.INVALID_REQUEST, "삭제 대상 종류와 ID가 필요합니다.");
		}
		String why = reason(reason);
		return switch (type) {
			case POST -> posts.lock(targetId).map(post -> {
				postService.deleteCascade(post);
				record(type, targetId, post.authorId(), post.neighborhoodCode(), requester, why);
				return true;
			}).orElse(false);
			case COMMENT -> deleteComment(targetId, requester, why);
		};
	}

	private boolean deleteComment(UUID commentId, Requester.Member requester, String why) {
		Optional<CommentRow> found = comments.findById(commentId).filter(c -> !c.deleted());
		if (found.isEmpty()) {
			return false;
		}
		CommentRow comment = found.get();
		Optional<PostRow> post = posts.findById(comment.postId());
		if (post.isEmpty() || !commentService.remove(comment)) {
			return false;
		}
		record(TargetType.COMMENT, commentId, comment.authorId(), post.get().neighborhoodCode(), requester, why);
		return true;
	}

	private void record(TargetType type, UUID targetId, UUID authorId, String neighborhoodCode,
			Requester.Member admin, String reason) {
		jdbc.sql("INSERT INTO community_admin_deletion (id, target_type, target_id, target_author_id, neighborhood_code, "
				+ "admin_id, reason, deleted_at) VALUES (:id, :type, :target, :author, :nbr, :admin, :reason, :at)")
			.param("id", UUID.randomUUID())
			.param("type", type.name())
			.param("target", targetId)
			.param("author", authorId)
			.param("nbr", neighborhoodCode)
			.param("admin", admin.memberId().value())
			.param("reason", reason)
			.param("at", Timestamp.from(now()))
			.update();
	}

	private String reason(String reason) {
		if (reason == null || reason.isBlank()) {
			throw new DomainException(ErrorCode.INVALID_REQUEST, "삭제 사유를 입력해 주세요.", Map.of("reason", "REASON_BLANK"));
		}
		String trimmed = reason.strip();
		if (trimmed.codePointCount(0, trimmed.length()) > properties.adminDeletion().reasonMaxLength()) {
			throw new DomainException(ErrorCode.INVALID_REQUEST,
					"삭제 사유는 " + properties.adminDeletion().reasonMaxLength() + "자 이하여야 합니다.",
					Map.of("reason", "REASON_TOO_LONG"));
		}
		return trimmed;
	}

	private Instant now() {
		return clock.instant().truncatedTo(ChronoUnit.MICROS);
	}

}
