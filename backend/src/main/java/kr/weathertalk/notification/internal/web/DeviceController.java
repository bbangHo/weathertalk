package kr.weathertalk.notification.internal.web;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import kr.weathertalk.member.Requester;
import kr.weathertalk.member.TemperatureSensitivity;
import kr.weathertalk.notification.DepartureTimes;
import kr.weathertalk.notification.DeviceConfirmCommand;
import kr.weathertalk.notification.DeviceConfirmation;
import kr.weathertalk.notification.DevicePlatform;
import kr.weathertalk.notification.NotificationApi;
import kr.weathertalk.notification.OsNotificationPermission;
import kr.weathertalk.notification.internal.DeviceService;
import kr.weathertalk.shared.Ids.DeviceId;
import kr.weathertalk.shared.Ids.NeighborhoodId;

/**
 * 기기 (FR-NOTI-01~05, 07(댓글 알림), 08, 17, 18). 비회원도 쓴다. 기기를 바꾸는 요청은 기기 키 헤더로 소유를 증명한다(BR-NOTI-15).
 * 기기 확인은 Authorization(있으면)으로 현재 요청자를 해석해 회원 연결을 대조한다 — 잘못된 토큰은 비회원으로 낮추지 않고 인증 실패다.
 */
@RestController
class DeviceController {

	/**
	 * @param deviceId·deviceKey 첫 호출(등록)이면 비운다
	 */
	record ConfirmRequest(UUID deviceId, String deviceKey, DevicePlatform platform, String pushToken,
			OsNotificationPermission osPermission) {

		@Override
		public String toString() {
			return "ConfirmRequest[deviceId=" + deviceId + ", platform=" + platform + "]";
		}

	}

	record ConfirmResponse(UUID deviceId, String deviceKey, boolean registered, String status, boolean linkedToMember,
			boolean commentAlertEnabled) {

		static ConfirmResponse from(DeviceConfirmation c) {
			return new ConfirmResponse(c.deviceId().value(), c.deviceKey(), c.registered(), c.status().name(),
					c.linkedToMember(), c.commentAlertEnabled());
		}

		@Override
		public String toString() {
			return "ConfirmResponse[deviceId=" + deviceId + ", registered=" + registered + "]";
		}

	}

	record NeighborhoodRequest(String neighborhoodId) {
	}

	record SensitivityRequest(TemperatureSensitivity temperatureSensitivity) {
	}

	record CommentAlertRequest(boolean enabled) {
	}

	record CommentAlertResponse(boolean enabled) {
	}

	record DepartureTimesRequest(List<LocalTime> departureTimes) {
	}

	private final NotificationApi notificationApi;
	private final DeviceService devices;

	DeviceController(NotificationApi notificationApi, DeviceService devices) {
		this.notificationApi = notificationApi;
		this.devices = devices;
	}

	/** 기기 확인 (contracts §2.7): 앱 시작·포그라운드 복귀·로그인·로그아웃 직후. 등록이면 deviceKey를 이 응답에서만 준다. */
	@PostMapping("/api/v1/notification/devices/confirm")
	ConfirmResponse confirm(Requester requester, @RequestBody ConfirmRequest request) {
		return ConfirmResponse.from(notificationApi.confirmDevice(new DeviceConfirmCommand(
				request.deviceId() == null ? null : new DeviceId(request.deviceId()), request.deviceKey(),
				request.platform(), request.pushToken(), request.osPermission()), requester));
	}

	/** 비회원 기기 현재 동네 보고 (FR-NOTI-05). 좌표는 받지 않는다(ADR 0009). */
	@PutMapping("/api/v1/notification/devices/me/neighborhood")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void reportNeighborhood(@RequestHeader(DeviceHeaders.DEVICE_ID) String deviceId,
			@RequestHeader(DeviceHeaders.DEVICE_KEY) String deviceKey, @RequestBody NeighborhoodRequest request) {
		devices.reportNeighborhood(DeviceHeaders.credential(deviceId, deviceKey),
				request.neighborhoodId() == null ? null : new NeighborhoodId(request.neighborhoodId()));
	}

	/** 비회원 알림용 민감도 (FR-NOTI-08). */
	@PutMapping("/api/v1/notification/devices/me/temperature-sensitivity")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void sensitivity(@RequestHeader(DeviceHeaders.DEVICE_ID) String deviceId,
			@RequestHeader(DeviceHeaders.DEVICE_KEY) String deviceKey, @RequestBody SensitivityRequest request) {
		devices.setGuestSensitivity(DeviceHeaders.credential(deviceId, deviceKey), request.temperatureSensitivity());
	}

	/** 기기 단위 댓글 알림 on/off (FR-NOTI-07). 켜기는 회원 연결 기기만. */
	@PutMapping("/api/v1/notification/devices/me/comment-alert")
	CommentAlertResponse commentAlert(@RequestHeader(DeviceHeaders.DEVICE_ID) String deviceId,
			@RequestHeader(DeviceHeaders.DEVICE_KEY) String deviceKey, @RequestBody CommentAlertRequest request) {
		return new CommentAlertResponse(
				devices.setCommentAlert(DeviceHeaders.credential(deviceId, deviceKey), request.enabled()));
	}

	/** 기기의 외출 시각 조회 (FR-NOTI-18) + 다음 외출 시각. */
	@GetMapping("/api/v1/notification/devices/me/departure-times")
	DepartureTimes departureTimes(@RequestHeader(DeviceHeaders.DEVICE_ID) String deviceId,
			@RequestHeader(DeviceHeaders.DEVICE_KEY) String deviceKey) {
		return notificationApi.findDepartureTimes(DeviceHeaders.credential(deviceId, deviceKey));
	}

	/** 기기의 외출 시각 설정 (FR-NOTI-17): 목록 통째 교체("HH:mm"). */
	@PutMapping("/api/v1/notification/devices/me/departure-times")
	DepartureTimes setDepartureTimes(@RequestHeader(DeviceHeaders.DEVICE_ID) String deviceId,
			@RequestHeader(DeviceHeaders.DEVICE_KEY) String deviceKey, @RequestBody DepartureTimesRequest request) {
		return notificationApi.setDepartureTimes(DeviceHeaders.credential(deviceId, deviceKey),
				request.departureTimes());
	}

}
