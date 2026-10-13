package kr.weathertalk.neighborhood;

import java.util.List;

import kr.weathertalk.shared.Ids.NeighborhoodId;

/**
 * 관심 동네 일괄 등록 결과 (FR-NBR-06).
 *
 * @param interests 최종 관심 동네(순서대로)
 * @param skipped 이미 등록됨·폐지·없는 동네라 건너뛴 것
 * @param discarded 최대 개수를 넘어 버린 것
 */
public record InterestMergeResult(List<Neighborhood> interests, List<NeighborhoodId> skipped,
		List<NeighborhoodId> discarded) {
}
