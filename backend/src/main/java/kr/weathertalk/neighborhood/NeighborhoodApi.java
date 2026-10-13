package kr.weathertalk.neighborhood;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import kr.weathertalk.shared.Ids.MemberId;
import kr.weathertalk.shared.Ids.NeighborhoodId;

/**
 * neighborhood 모듈의 공개 인터페이스 (contracts.md §2.2). 다른 모듈은 이 타입으로만 neighborhood 기능을 사용한다.
 * <p>
 * 좌표({@link DeviceLocation})는 판정에만 쓰고 저장·로그에 남기지 않는다(ADR 0009).
 * 알림 구독 동네 반영(FR-NBR-10)은 notification만 부르는 명령이다(역방향 이벤트 구독 대신, contracts.md §1).
 */
public interface NeighborhoodApi {

	/**
	 * 좌표로 동네 판정 (FR-NBR-01). 조회용이며 아무것도 저장하지 않는다.
	 *
	 * @return 좌표가 속한 유효 동네(경계가 없으면 가장 가까운 대표점의 동네, ADR 0016). 가장 가까운 대표점도
	 *         neighborhood.max-nearest-distance보다 멀면(바다 위·국외) 빈 값
	 * @throws kr.weathertalk.shared.error.DomainException INVALID_REQUEST — 좌표가 없거나 범위를 벗어남
	 */
	Optional<Neighborhood> locate(DeviceLocation location);

	/**
	 * 현재 동네 확정 (FR-NBR-03). 체감 기록·체감 피드백·현장 제보·게시글 작성 직전에 반드시 호출한다.
	 * 정확도 기준(neighborhood.max-accuracy-for-write) 미달·판정 불가는 거부 사유로 돌려준다.
	 * 확정되면 그 회원의 현재 동네와 확정 시각을 저장한다.
	 */
	CurrentNeighborhoodConfirmation confirmCurrentNeighborhood(MemberId memberId, DeviceLocation location);

	/** 동네 조회 (단건). 폐지된 동네도 돌려준다(과거 기록 표시용). */
	Optional<Neighborhood> findNeighborhood(NeighborhoodId id);

	/** 동네 조회 (일괄). 없는 ID는 빠진다. */
	Map<NeighborhoodId, Neighborhood> findNeighborhoods(Collection<NeighborhoodId> ids);

	/** 동네 검색 (FR-NBR-07): 유효 동네만, 읍면동 이름 일치 우선. */
	List<Neighborhood> search(String query);

	/** 회원의 현재 동네 조회 (FR-NBR-04). 없음은 정상 결과다(BR-NBR-10). */
	Optional<CurrentNeighborhood> findCurrentNeighborhood(MemberId memberId);

	/** 회원의 현재 동네 일괄 조회. 현재 동네가 없는 회원은 빠진다. */
	Map<MemberId, CurrentNeighborhood> findCurrentNeighborhoods(Collection<MemberId> memberIds);

	/** 회원의 관심 동네 조회 (순서대로). */
	List<Neighborhood> findInterestNeighborhoods(MemberId memberId);

	/** 관심 동네 일괄 등록 (FR-NBR-06): 가입·로그인 직후 기기 보관분을 서버 목록 뒤에 합친다. 멱등. */
	InterestMergeResult mergeInterestNeighborhoods(MemberId memberId, List<NeighborhoodId> neighborhoodIds);

	/**
	 * 알림 구독 동네 반영 (FR-NBR-10): 동네의 알림 구독 수를 delta만큼 바꾼다. notification이 구독 생성·해제·동네 변경·기기 삭제마다
	 * 자기 트랜잭션 안에서 부른다. 0 아래로 내려가는 감소는 0에서 멈추고 경고 로그를 남긴다.
	 *
	 * @throws kr.weathertalk.shared.error.DomainException NOT_FOUND — 없는 동네
	 */
	void applyNotificationSubscriptionChange(NeighborhoodId neighborhoodId, int delta);

	/**
	 * 알림 구독 수 전체 대조 (02 §9 미결 8 — 보정 작업을 두는 쪽으로 구현): notification이 진실 원천으로 계산한 동네별 구독 수로
	 * 통째로 바꾼다. 목록에 없는 동네는 0이 된다. 증감 호출의 누락·중복으로 생긴 어긋남을 바로잡는다.
	 *
	 * @return 값이 바뀐 동네 수
	 */
	int reconcileNotificationSubscriptions(Map<NeighborhoodId, Integer> counts);

	/** 활성 동네 목록 (FR-NBR-09). */
	List<NeighborhoodId> findActiveNeighborhoods();

	/** 활성 예보 격자 목록 (FR-NBR-09) — weather 사전 갱신 대상. */
	List<ForecastGrid> findActiveForecastGrids();

	/** 예보 격자의 활성 동네 목록 (FR-NBR-09) — weather 강수 시작 감지 시 대상 동네. */
	List<NeighborhoodId> findActiveNeighborhoods(ForecastGrid grid);

}
