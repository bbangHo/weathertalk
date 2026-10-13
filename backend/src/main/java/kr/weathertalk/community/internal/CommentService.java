package kr.weathertalk.community.internal;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.weathertalk.community.CommentCreated;
import kr.weathertalk.community.internal.CommentRepository.CommentRow;
import kr.weathertalk.community.internal.PostRepository.PostRow;
import kr.weathertalk.community.internal.config.CommunityProperties;
import kr.weathertalk.shared.Ids.CommentId;
import kr.weathertalk.shared.Ids.MemberId;
import kr.weathertalk.shared.Ids.PostId;
import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;

/**
 * 댓글·답글 작성·수정·삭제 (FR-COM-07·08·09). 게시글의 동네와 관계없이 쓸 수 있다 — 위치 확인을 하지 않는다(BR-COM-02).
 * <p>
 * 삭제 전이(§3.3): 답글은 그냥 지우고, 상위가 삭제된 댓글 자리인데 남은 답글이 없으면 그 자리도 지운다.
 * 댓글은 답글이 있으면 삭제된 댓글 자리(작성자·본문 제거)로, 없으면 지운다. 작성자 삭제·관리자 삭제·탈퇴 정리가 같은 규칙을 쓴다.
 */
@Service
public class CommentService {

	// 댓글 본문 최소 길이는 설정 파라미터가 아니라 §3.3 고정 규칙(공백 제외 1자 이상)이다
	private static final int MIN_LENGTH = 1;

	private final CommentRepository comments;
	private final PostRepository posts;
	private final ApplicationEventPublisher events;
	private final CommunityProperties properties;
	private final Clock clock;

	CommentService(CommentRepository comments, PostRepository posts, ApplicationEventPublisher events,
			CommunityProperties properties, Clock clock) {
		this.comments = comments;
		this.posts = posts;
		this.events = events;
		this.properties = properties;
		this.clock = clock;
	}

	/** 댓글 작성 (FR-COM-07). 본인 글에 단 댓글도 댓글작성됨을 발행한다(알림 제외 판정은 notification). */
	@Transactional
	public CommentId comment(MemberId memberId, UUID postId, String body) {
		String text = commentBody(body);
		PostRow post = posts.findById(postId)
			.orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "삭제되었거나 없는 게시글입니다."));
		return insert(memberId, post, null, text);
	}

	/** 답글 작성 (FR-COM-08). 상위는 게시 중인 댓글이어야 한다 — 답글의 답글·삭제된 댓글에는 달 수 없다. */
	@Transactional
	public CommentId reply(MemberId memberId, UUID parentCommentId, String body) {
		String text = commentBody(body);
		CommentRow parent = comments.lock(parentCommentId)
			.orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "삭제되었거나 없는 댓글입니다."));
		if (parent.reply()) {
			throw notReplyable("REPLY_TO_REPLY", "답글에는 답글을 달 수 없습니다. 같은 댓글 아래에 이어 달아 주세요.");
		}
		if (parent.deleted()) {
			throw notReplyable("PARENT_DELETED", "삭제된 댓글에는 답글을 달 수 없습니다.");
		}
		PostRow post = posts.findById(parent.postId())
			.orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "삭제되었거나 없는 게시글입니다."));
		return insert(memberId, post, parent, text);
	}

	private CommentId insert(MemberId memberId, PostRow post, CommentRow parent, String text) {
		UUID id = UUID.randomUUID();
		Instant now = now();
		comments.insert(id, post.id(), parent == null ? null : parent.id(), memberId.value(), text, now);
		events.publishEvent(new CommentCreated(UUID.randomUUID(), new CommentId(id), memberId, new PostId(post.id()),
				new MemberId(post.authorId()), parent == null ? null : new CommentId(parent.id()),
				parent == null ? null : new MemberId(parent.authorId()), now));
		return new CommentId(id);
	}

	/** 본문만 수정한다. 삭제된 댓글 자리는 수정할 수 없다(없는 댓글로 응답). */
	@Transactional
	public void update(MemberId memberId, UUID commentId, String body) {
		String text = commentBody(body);
		CommentRow comment = comments.lock(commentId)
			.filter(c -> !c.deleted())
			.orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "삭제되었거나 없는 댓글입니다."));
		requireAuthor(comment, memberId);
		comments.updateBody(commentId, text, now());
	}

	/** 작성자 삭제. 이미 없는 댓글(삭제된 댓글 자리 포함)이면 성공(멱등). */
	@Transactional
	public void delete(MemberId memberId, UUID commentId) {
		comments.findById(commentId).filter(c -> !c.deleted()).ifPresent(comment -> {
			requireAuthor(comment, memberId);
			remove(comment);
		});
	}

	/**
	 * §3.3 삭제 전이. 상위 댓글 → 대상 순서로 잠가 답글 작성(상위 댓글 잠금)과 순서를 맞춘다.
	 *
	 * @return 지웠거나 자리로 바꿨으면 true, 이미 없었으면 false
	 */
	boolean remove(CommentRow target) {
		if (target.reply()) {
			CommentRow parent = comments.lock(target.parentId()).orElse(null);
			if (comments.lock(target.id()).isEmpty()) {
				return false;
			}
			comments.delete(target.id());
			if (parent != null && parent.deleted() && comments.countReplies(parent.id()) == 0) {
				comments.delete(parent.id());
			}
			return true;
		}
		CommentRow current = comments.lock(target.id()).filter(c -> !c.deleted()).orElse(null);
		if (current == null) {
			return false;
		}
		if (comments.countReplies(current.id()) > 0) {
			comments.markDeleted(current.id());
		}
		else {
			comments.delete(current.id());
		}
		return true;
	}

	private String commentBody(String body) {
		return TextRules.body(body, MIN_LENGTH, properties.comment().maxLength(), "댓글");
	}

	private static void requireAuthor(CommentRow comment, MemberId memberId) {
		if (!memberId.value().equals(comment.authorId())) {
			throw new DomainException(ErrorCode.NOT_CONTENT_AUTHOR, "작성자만 할 수 있습니다.");
		}
	}

	private static DomainException notReplyable(String reason, String message) {
		return new DomainException(ErrorCode.REPLY_NOT_ALLOWED, message, Map.of("reason", reason));
	}

	private Instant now() {
		return clock.instant().truncatedTo(ChronoUnit.MICROS);
	}

}
