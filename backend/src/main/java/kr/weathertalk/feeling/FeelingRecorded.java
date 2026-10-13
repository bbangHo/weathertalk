package kr.weathertalk.feeling;

import java.time.Instant;
import java.util.UUID;

import kr.weathertalk.shared.Ids.FeelingRecordId;
import kr.weathertalk.shared.Ids.MemberId;
import kr.weathertalk.shared.Ids.NeighborhoodId;

/**
 * 체감기록됨 (contracts.md §3). 기록 저장 트랜잭션에서 발행한다. 현재 구독자 없음(향후 게이미피케이션).
 *
 * @param source 저장된 기록의 출처(직접/피드백/직접+피드백 보정)
 */
public record FeelingRecorded(UUID eventId, MemberId memberId, FeelingRecordId feelingRecordId,
		NeighborhoodId neighborhoodId, Instant recordedAt, FeelingRecordSource source, Change change,
		Instant occurredAt) {

	public enum Change {

		CREATED, OVERWRITTEN

	}

}
