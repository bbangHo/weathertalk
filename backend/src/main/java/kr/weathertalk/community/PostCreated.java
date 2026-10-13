package kr.weathertalk.community;

import java.time.Instant;
import java.util.UUID;

import kr.weathertalk.shared.Ids.MemberId;
import kr.weathertalk.shared.Ids.NeighborhoodId;
import kr.weathertalk.shared.Ids.PostId;

/**
 * 게시글작성됨 (contracts.md §3). 게시글 작성 트랜잭션에서 발행한다. 현재 구독자 없음(향후 게이미피케이션).
 * 구독자가 없으면 이벤트 발행 저장소에 행이 생기지 않는다(구독자별로 영속화되므로 미완료로 쌓이지 않음).
 */
public record PostCreated(UUID eventId, MemberId authorId, PostId postId, NeighborhoodId neighborhoodId,
		Instant occurredAt) {
}
