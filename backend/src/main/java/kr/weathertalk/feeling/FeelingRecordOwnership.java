package kr.weathertalk.feeling;

import java.time.Instant;

import kr.weathertalk.shared.Ids.NeighborhoodId;

/**
 * 체감 기록 소유 확인 결과 (FR-FEL-10, community 첨부 검증용). 소유가 아니면 동네·시각은 null.
 * 탈퇴로 익명화된 기록은 누구의 것도 아니다.
 */
public record FeelingRecordOwnership(boolean owned, NeighborhoodId neighborhoodId, Instant recordedAt) {

	public static FeelingRecordOwnership notOwned() {
		return new FeelingRecordOwnership(false, null, null);
	}

}
