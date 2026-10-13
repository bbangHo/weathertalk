package kr.weathertalk.home;

import java.time.LocalTime;
import java.util.List;

import kr.weathertalk.member.Requester;
import kr.weathertalk.member.TemperatureSensitivity;
import kr.weathertalk.shared.Ids.NeighborhoodId;

/**
 * home 모듈의 공개 인터페이스. 홈 화면(SCR-01) 조합 (07 §1). 저장·판단 없이 각 모듈 공개 인터페이스를 호출해 섹션으로 묶는다.
 */
public interface HomeApi {

	/**
	 * @param neighborhoodId 보는 동네. 비회원은 필수, 회원은 없으면 회원의 현재 동네
	 * @param deviceSensitivity 비회원이 기기에서 고른 온도 민감도(없을 수 있음). 회원이면 무시한다
	 * @param departureTimes 기기의 외출 시각(Asia/Seoul). 외출 시각 보관(notification)은 M7이라 그 전까지 요청으로 받는다. 비면 섹션 생략
	 * @throws kr.weathertalk.shared.error.DomainException VIEWING_NEIGHBORHOOD_UNRESOLVED — 보는 동네를 정할 수 없음(화면 전체 실패의 유일한 경우)
	 */
	HomeView compose(Requester requester, NeighborhoodId neighborhoodId, TemperatureSensitivity deviceSensitivity,
			List<LocalTime> departureTimes);

}
