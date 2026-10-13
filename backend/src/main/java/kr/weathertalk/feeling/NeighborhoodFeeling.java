package kr.weathertalk.feeling;

import java.time.Instant;
import java.util.Map;

import kr.weathertalk.member.TemperatureSensitivity;
import kr.weathertalk.shared.Ids.NeighborhoodId;

/**
 * 동네 체감 조회 결과 (FR-FEL-03, 04 §3.4). 집계 창은 (windowStart, basisTime].
 *
 * @param groups 온도 민감도 세 그룹 모두를 담는다(기록 없는 그룹도 '기록 없음'으로)
 */
public record NeighborhoodFeeling(NeighborhoodId neighborhoodId, Instant basisTime, Instant windowStart,
		CategorySummaries overall, Map<TemperatureSensitivity, CategorySummaries> groups) {
}
