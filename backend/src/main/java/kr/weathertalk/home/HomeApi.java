package kr.weathertalk.home;

import kr.weathertalk.member.Requester;
import kr.weathertalk.shared.Ids.NeighborhoodId;

/**
 * home 모듈의 공개 인터페이스. 홈 화면(SCR-01) 조합 (07 §1). 저장·판단 없이 각 모듈 공개 인터페이스를 호출해 섹션으로 묶는다.
 */
public interface HomeApi {

	/**
	 * @param neighborhoodId 보는 동네. 비회원은 필수, 회원은 없으면 회원의 현재 동네
	 * @throws kr.weathertalk.shared.error.DomainException VIEWING_NEIGHBORHOOD_UNRESOLVED — 보는 동네를 정할 수 없음(화면 전체 실패의 유일한 경우)
	 */
	HomeView compose(Requester requester, NeighborhoodId neighborhoodId);

}
