package kr.weathertalk.member;

import java.time.Instant;
import java.util.UUID;

import kr.weathertalk.shared.Ids.MemberId;

/**
 * 온도민감도변경됨 (contracts.md §3, FR-MEM-08). 값이 실제로 바뀐 경우에만 발행한다.
 */
public record TemperatureSensitivityChanged(UUID eventId, MemberId memberId, TemperatureSensitivity previous,
		TemperatureSensitivity current, SensitivityChangeSource source, Instant occurredAt) {
}
