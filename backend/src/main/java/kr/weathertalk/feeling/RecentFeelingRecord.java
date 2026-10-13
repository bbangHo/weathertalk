package kr.weathertalk.feeling;

import java.time.Instant;

import kr.weathertalk.shared.Ids.FeelingRecordId;

/**
 * 회원의 최근 체감 기록 (FR-FEL-10): 그 동네에서 생성 시각이 feeling.record-interval 안인 기록. 피드백 버튼 상태
 * (이미 기록함 / 다시 답하면 이전 기록을 바꿈) 표시용.
 *
 * @param overwritableUntil 이 시각 전의 기록·피드백은 이 기록을 덮어쓴다(생성 시각 + 간격, 덮어써도 늘지 않음)
 */
public record RecentFeelingRecord(FeelingRecordId id, FeelingRecordSource source, TemperatureTag temperatureTag,
		Instant recordedAt, Instant createdAt, Instant overwritableUntil) {
}
