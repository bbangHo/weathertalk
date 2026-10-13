package kr.weathertalk.weather;

import java.time.Instant;
import java.util.UUID;

import kr.weathertalk.shared.Ids.NeighborhoodId;

/**
 * 현장 제보 한 건 (03 §3.7). 제보자 회원 ID는 담지 않는다 — 본인 제보 응답에만 쓰인다.
 *
 * @param cancelableUntil 제보 시각 + weather.field-report.cancel-window. 이 시각까지 본인이 취소할 수 있다(FR-WEA-14)
 * @param canceledAt 취소했으면 그 시각, 아니면 null
 */
public record FieldReport(UUID reportId, NeighborhoodId neighborhoodId, FieldReportAxis axis, FieldReportKind kind,
		Instant reportedAt, Instant cancelableUntil, Instant canceledAt) {
}
