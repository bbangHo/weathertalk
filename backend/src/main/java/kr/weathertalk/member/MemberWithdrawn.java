package kr.weathertalk.member;

import java.time.Instant;
import java.util.UUID;

import kr.weathertalk.shared.Ids.MemberId;

/**
 * 회원탈퇴함 (contracts.md §3). 탈퇴 로컬 확정 트랜잭션에서 발행하며 이벤트 발행 저장소에 영속화된다.
 * 구독 모듈(neighborhood, weather, feeling, community, notification)은 이벤트가 다시 전달될 수 있으므로 멱등하게 처리한다.
 */
public record MemberWithdrawn(UUID eventId, MemberId memberId, Instant occurredAt) {
}
