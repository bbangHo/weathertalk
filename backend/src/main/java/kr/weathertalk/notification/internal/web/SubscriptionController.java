package kr.weathertalk.notification.internal.web;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import kr.weathertalk.member.TemperatureSensitivity;
import kr.weathertalk.notification.internal.SubscriptionService;
import kr.weathertalk.notification.internal.SubscriptionStore.TargetType;
import kr.weathertalk.shared.Ids.NeighborhoodId;

/**
 * 알림 구독 (FR-NOTI-06·07). 비회원도 쓴다. 기기 키 헤더로 소유를 증명한다(BR-NOTI-15).
 * 구독 키는 (기기, 구독 동네)이며 PUT 하나로 생성·변경한다. 세 종류를 모두 끄면 지워지고 응답 본문은 비어 있다(204).
 */
@RestController
class SubscriptionController {

	/**
	 * @param targetType FOLLOW_CURRENT(현재 동네 따라가기) / FIXED(지정 동네)
	 * @param dailyTimes "HH:mm" 목록. 생략하고 정기 날씨 알림을 켜면 notification.daily.default-time 하나. 빈 목록이면 정기 날씨 알림 꺼짐
	 */
	record SubscriptionRequest(TargetType targetType, String neighborhoodId, boolean dailyEnabled,
			List<LocalTime> dailyTimes, boolean precipitationForecastEnabled, boolean fieldReportPrecipitationEnabled) {
	}

	record SubscriptionResponse(UUID id, TargetType targetType, String neighborhoodId, String neighborhoodName,
			String resolvedNeighborhoodId, String resolvedNeighborhoodName, boolean dailyEnabled,
			List<LocalTime> dailyTimes, boolean precipitationForecastEnabled, boolean fieldReportPrecipitationEnabled,
			Instant consentedAt) {

		static SubscriptionResponse from(SubscriptionService.View v) {
			return new SubscriptionResponse(v.id(), v.targetType(), v.neighborhoodId(), v.neighborhoodName(),
					v.resolvedNeighborhoodId(), v.resolvedNeighborhoodName(), v.dailyEnabled(), v.dailyTimes(),
					v.precipitationForecastEnabled(), v.fieldReportPrecipitationEnabled(), v.consentedAt());
		}

	}

	/**
	 * @param guestSensitivity 비회원 기기의 알림용 민감도(회원 연결 기기면 null)
	 */
	record SettingsResponse(boolean linkedToMember, boolean commentAlertEnabled, TemperatureSensitivity guestSensitivity,
			String deviceNeighborhoodId, List<SubscriptionResponse> subscriptions) {
	}

	private final SubscriptionService subscriptions;

	SubscriptionController(SubscriptionService subscriptions) {
		this.subscriptions = subscriptions;
	}

	/** 알림 구독 조회 (FR-NOTI-06). 구독이 없으면 빈 목록. */
	@GetMapping("/api/v1/notification/subscriptions")
	SettingsResponse list(@RequestHeader(DeviceHeaders.DEVICE_ID) String deviceId,
			@RequestHeader(DeviceHeaders.DEVICE_KEY) String deviceKey) {
		SubscriptionService.Settings s = subscriptions.settings(DeviceHeaders.credential(deviceId, deviceKey));
		return new SettingsResponse(s.linkedToMember(), s.commentAlertEnabled(), s.guestSensitivity(),
				s.deviceNeighborhoodId(), s.subscriptions().stream().map(SubscriptionResponse::from).toList());
	}

	/** 알림 구독 생성·변경 (FR-NOTI-07). 모두 꺼지면 삭제되고 204. */
	@PutMapping("/api/v1/notification/subscriptions")
	ResponseEntity<SubscriptionResponse> upsert(
			@RequestHeader(DeviceHeaders.DEVICE_ID) String deviceId,
			@RequestHeader(DeviceHeaders.DEVICE_KEY) String deviceKey, @RequestBody SubscriptionRequest request) {
		return subscriptions
			.upsert(DeviceHeaders.credential(deviceId, deviceKey),
					new SubscriptionService.Command(target(request.targetType(), request.neighborhoodId()),
							request.dailyEnabled(), request.dailyTimes(), request.precipitationForecastEnabled(),
							request.fieldReportPrecipitationEnabled()))
			.map(v -> ResponseEntity.ok(SubscriptionResponse.from(v)))
			.orElseGet(() -> ResponseEntity.noContent().build());
	}

	/** 알림 구독 삭제. 없으면 그대로 성공. */
	@DeleteMapping("/api/v1/notification/subscriptions")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(@RequestHeader(DeviceHeaders.DEVICE_ID) String deviceId,
			@RequestHeader(DeviceHeaders.DEVICE_KEY) String deviceKey, @RequestParam TargetType targetType,
			@RequestParam(required = false) String neighborhoodId) {
		subscriptions.delete(DeviceHeaders.credential(deviceId, deviceKey), target(targetType, neighborhoodId));
	}

	private static SubscriptionService.Target target(TargetType type, String neighborhoodId) {
		return new SubscriptionService.Target(type,
				neighborhoodId == null || neighborhoodId.isBlank() ? null : new NeighborhoodId(neighborhoodId.strip()));
	}

}
