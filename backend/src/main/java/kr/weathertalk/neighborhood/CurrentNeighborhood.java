package kr.weathertalk.neighborhood;

import java.time.Instant;

import kr.weathertalk.shared.Ids.MemberId;

/**
 * 회원의 현재 동네 (FR-NBR-04). 표시·활성 동네 산출용이며 기록·작성 검증에는 쓰지 않는다(그때는 현재 동네 확정).
 *
 * @param confirmedAt 마지막으로 좌표 판정이 성공한 시각
 */
public record CurrentNeighborhood(MemberId memberId, Neighborhood neighborhood, Instant confirmedAt) {
}
