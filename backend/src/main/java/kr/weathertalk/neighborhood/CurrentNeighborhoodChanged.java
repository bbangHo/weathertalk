package kr.weathertalk.neighborhood;

import java.time.Instant;
import java.util.UUID;

import kr.weathertalk.shared.Ids.MemberId;
import kr.weathertalk.shared.Ids.NeighborhoodId;

/**
 * 현재동네변경됨 (contracts.md §3, 02 §6). 좌표는 싣지 않는다.
 *
 * @param previous 이전 동네. 처음이면 null
 */
public record CurrentNeighborhoodChanged(UUID eventId, MemberId memberId, NeighborhoodId previous,
		NeighborhoodId current, Instant occurredAt) {
}
