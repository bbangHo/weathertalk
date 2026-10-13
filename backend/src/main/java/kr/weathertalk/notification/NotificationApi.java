package kr.weathertalk.notification;

import java.time.LocalTime;
import java.util.List;

import kr.weathertalk.member.Requester;

/**
 * notification 모듈의 공개 인터페이스 (contracts.md §2.7). 다른 모듈은 이 타입으로만 notification 기능을 사용한다.
 * 알림 구독·기기 단위 설정 변경(FR-NOTI-05~08)은 앱에만 제공한다(웹 API).
 */
public interface NotificationApi {

	/**
	 * 기기 확인 (FR-NOTI-01~04): 기기 등록·푸시 토큰 갱신·OS 권한 갱신·회원 연결 대조. 앱 시작·포그라운드 복귀·로그인·로그아웃 직후 호출한다.
	 * 로그인/로그아웃 이벤트 없이 이 호출이 기기-회원 연결의 진실 원천이다(BR-NOTI-04).
	 *
	 * @throws kr.weathertalk.shared.error.DomainException NOT_FOUND(reason=DEVICE_NOT_FOUND, 삭제된 기기 — 다시 등록),
	 *         DEVICE_KEY_MISMATCH, INVALID_REQUEST(형식 오류)
	 */
	DeviceConfirmation confirmDevice(DeviceConfirmCommand command, Requester requester);

	/**
	 * 기기의 외출 시각 조회 (FR-NOTI-18) + 다음 외출 시각 선택.
	 *
	 * @throws kr.weathertalk.shared.error.DomainException NOT_FOUND, DEVICE_KEY_MISMATCH
	 */
	DepartureTimes findDepartureTimes(DeviceCredential device);

	/**
	 * 기기의 외출 시각 설정 (FR-NOTI-17): 목록 통째 교체(추가·수정·삭제). 0~notification.departure.max-times개, time-unit 단위, 중복 불가.
	 *
	 * @throws kr.weathertalk.shared.error.DomainException INVALID_REQUEST, NOT_FOUND, DEVICE_KEY_MISMATCH
	 */
	DepartureTimes setDepartureTimes(DeviceCredential device, List<LocalTime> times);

}
