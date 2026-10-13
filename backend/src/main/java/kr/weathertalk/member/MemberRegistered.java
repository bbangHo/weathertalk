package kr.weathertalk.member;

import java.time.Instant;
import java.util.UUID;

import kr.weathertalk.shared.Ids.MemberId;

/**
 * 회원가입됨 (contracts.md §3). 가입 완료 트랜잭션에서 발행한다.
 *
 * @param eventId 구독자 멱등 처리용 이벤트 ID
 */
public record MemberRegistered(UUID eventId, MemberId memberId, Instant occurredAt) {
}
