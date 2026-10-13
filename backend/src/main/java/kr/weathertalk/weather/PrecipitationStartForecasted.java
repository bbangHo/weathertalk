package kr.weathertalk.weather;

import java.time.Instant;
import java.util.UUID;

import kr.weathertalk.shared.Ids.NeighborhoodId;

/**
 * 강수시작예보됨 (contracts.md §3, BR-WEA-20). 활성 동네마다 같은 강수 구간에 1건만 발행한다. 새 발표에서 강수 예보가 사라져도
 * 취소 이벤트는 없다(Q57). 심야 금지·쿨다운은 구독자(M7 notification)가 적용한다.
 *
 * @param expectedStartAt 예상 시작 시각(강수 조건을 만족한 가장 이른 예보 칸)
 * @param precipitationPhrase 그 칸의 강수 문구(BR-WEA-13, 알림 문구에 그대로 쓴다)
 * @param basisIssuedAt 근거 단기예보 발표 시각
 */
public record PrecipitationStartForecasted(UUID eventId, NeighborhoodId neighborhoodId, Instant expectedStartAt,
		PrecipitationType precipitationType, String precipitationPhrase, Instant basisIssuedAt) {
}
