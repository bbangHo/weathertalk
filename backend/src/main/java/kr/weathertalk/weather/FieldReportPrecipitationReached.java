package kr.weathertalk.weather;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import kr.weathertalk.shared.Ids.MemberId;
import kr.weathertalk.shared.Ids.NeighborhoodId;

/**
 * 현장제보강수기준도달함 (contracts.md §3, BR-WEA-17). (동네, 강수 형태)마다 weather.field-report.rain-suppress 안에 1건만 발행한다.
 * 구독자(M7 notification)는 강수 형태로 문구('비'/'눈')를 구분하고, 제보 회원 ID 목록으로 행위자 본인을 뺀다(ADR 0008).
 * 이후 제보가 취소돼도 정정 이벤트는 없다(FR-WEA-14).
 *
 * @param precipitationType RAIN 또는 SNOW
 * @param reporterCount 기준 도달 시점에 센 서로 다른 제보자 수(탈퇴로 회원 ID가 비워진 제보 포함)
 * @param reporterIds 제보 회원 ID 목록(탈퇴 회원 제외)
 */
public record FieldReportPrecipitationReached(UUID eventId, NeighborhoodId neighborhoodId,
		PrecipitationType precipitationType, Instant reachedAt, int reporterCount, List<MemberId> reporterIds) {
}
