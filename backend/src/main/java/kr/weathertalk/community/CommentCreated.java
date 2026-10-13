package kr.weathertalk.community;

import java.time.Instant;
import java.util.UUID;

import kr.weathertalk.shared.Ids.CommentId;
import kr.weathertalk.shared.Ids.MemberId;
import kr.weathertalk.shared.Ids.PostId;

/**
 * 댓글작성됨 (contracts.md §3, 05 §6). 댓글·답글 작성 트랜잭션에서 발행한다. 본인 글에 단 댓글도 발행하며,
 * 알림 수신자 판정(본인 제외, 상위 댓글 작성자 포함)은 구독자(M7 notification)가 한다.
 *
 * @param parentCommentId 답글이면 상위 댓글 ID, 댓글이면 null
 * @param parentCommentAuthorId 답글이면 상위 댓글 작성자 ID, 댓글이면 null (05 계약 변경 제안 C5)
 */
public record CommentCreated(UUID eventId, CommentId commentId, MemberId commenterId, PostId postId,
		MemberId postAuthorId, CommentId parentCommentId, MemberId parentCommentAuthorId, Instant occurredAt) {
}
