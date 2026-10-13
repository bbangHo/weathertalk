package kr.weathertalk.notification;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

/**
 * 기기의 외출 시각 조회 결과 (contracts.md §2.7, FR-NOTI-18). 외출 시각은 기기 단위 설정이다(비회원 포함).
 *
 * @param departures 시각 순. 없으면 빈 목록(오류 아님)
 * @param nextDepartureAt 다음 외출 시각 — 오늘 남은 것 중 가장 가까운 시각, 없으면 내일 첫 시각. 외출 시각이 없으면 null.
 *        선택 규칙은 notification 소유이며 home은 표시만 한다
 */
public record DepartureTimes(List<Departure> departures, Instant nextDepartureAt) {

	/**
	 * @param time 외출 시각(Asia/Seoul, notification.daily.time-unit 단위)
	 * @param nextAt 이 시각의 다음 도래 시각. 그 시(時)가 아직 지나지 않았으면 오늘, 지났으면 내일
	 */
	public record Departure(LocalTime time, Instant nextAt) {
	}

	public boolean isEmpty() {
		return departures.isEmpty();
	}

}
