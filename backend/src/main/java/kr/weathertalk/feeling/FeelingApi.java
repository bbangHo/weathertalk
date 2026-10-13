package kr.weathertalk.feeling;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

import kr.weathertalk.member.TemperatureSensitivity;
import kr.weathertalk.shared.Ids.FeelingRecordId;
import kr.weathertalk.shared.Ids.MemberId;
import kr.weathertalk.shared.Ids.NeighborhoodId;

/**
 * feeling 모듈의 공개 인터페이스 (contracts.md §2.4). 다른 모듈은 이 타입으로만 feeling 기능을 사용한다.
 * <p>
 * 체감 기록·피드백 쓰기는 앱 요청 명령이라 공개 인터페이스에 두지 않는다. 민감도 보정 제안 조회·거절은 M6에서 추가한다.
 * 시각 인자가 null이면 현재 시각이다. 없는 동네는 NOT_FOUND.
 */
public interface FeelingApi {

	/**
	 * 민감도별 체감 조회 단건 (FR-FEL-04).
	 *
	 * @return '산출 불가'(동네 체감 부족 + 기온 없음, 또는 예보 범위 밖 미래)면 빈 값
	 * @throws kr.weathertalk.shared.error.DomainException INVALID_REQUEST — 과거 시각이 feeling.past-lookup-limit 밖
	 */
	Optional<SensitivityFeeling> findSensitivityFeeling(NeighborhoodId neighborhoodId,
			TemperatureSensitivity sensitivity, Instant at);

	/** 민감도별 체감 조회 3그룹 일괄 (FR-FEL-04). 기상값·동네 체감을 한 번만 조회한다. */
	SensitivityFeelings findSensitivityFeelings(NeighborhoodId neighborhoodId, Instant at);

	/** 동네 체감 조회 (FR-FEL-03). 기록이 없는 것은 실패가 아니라 빈 결과다. */
	NeighborhoodFeeling findNeighborhoodFeeling(NeighborhoodId neighborhoodId, Instant at);

	/** 어제와 비교 조회 (FR-FEL-05). */
	YesterdayComparison compareWithYesterday(NeighborhoodId neighborhoodId, TemperatureSensitivity sensitivity,
			Instant at);

	/** 체감 기록 소유 확인 (FR-FEL-10). */
	FeelingRecordOwnership checkOwnership(MemberId memberId, FeelingRecordId recordId);

	/** 체감 기록 조회 일괄 (FR-FEL-10). 없는 ID는 빠지고, 익명화된 기록도 조회된다. */
	Map<FeelingRecordId, FeelingRecordView> findRecords(Collection<FeelingRecordId> recordIds);

	/** 회원의 최근 체감 기록 조회 (FR-FEL-10, 피드백 버튼 상태용). */
	Optional<RecentFeelingRecord> findRecentRecord(MemberId memberId, NeighborhoodId neighborhoodId);

	/** 회원의 체감 기록 목록 조회 + 내 체감 패턴 (FR-FEL-11). */
	MyFeelingRecords findMyRecords(MemberId memberId);

}
