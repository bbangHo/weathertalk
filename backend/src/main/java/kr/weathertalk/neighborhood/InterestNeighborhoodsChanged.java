package kr.weathertalk.neighborhood;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import kr.weathertalk.shared.Ids.MemberId;
import kr.weathertalk.shared.Ids.NeighborhoodId;

/**
 * 관심동네변경됨 (contracts.md §3, 02 §6).
 */
public record InterestNeighborhoodsChanged(UUID eventId, MemberId memberId, List<NeighborhoodId> added,
		List<NeighborhoodId> removed, Instant occurredAt) {
}
