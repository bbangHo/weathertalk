package kr.weathertalk.community;

import java.time.Instant;
import java.util.UUID;

import kr.weathertalk.shared.Ids.MemberId;
import kr.weathertalk.shared.Ids.PostId;

/**
 * 좋아요함 (contracts.md §3). "아님 → 좋아요"로 실제 바뀐 경우에만 발행한다(FR-COM-11). 해제 후 다시 누르면 다시 발행되므로
 * 구독자는 (회원, 게시글) 기준으로 중복을 걸러야 한다(05 §9 Q8). 현재 구독자 없음.
 */
public record PostLiked(UUID eventId, MemberId memberId, PostId postId, Instant occurredAt) {
}
