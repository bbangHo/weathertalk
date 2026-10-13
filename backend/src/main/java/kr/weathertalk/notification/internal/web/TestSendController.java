package kr.weathertalk.notification.internal.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import kr.weathertalk.notification.internal.Dispatcher;
import kr.weathertalk.notification.internal.NotificationKind;
import kr.weathertalk.notification.internal.TestSendService;
import kr.weathertalk.shared.Ids.NeighborhoodId;
import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;
import kr.weathertalk.shared.profile.DevelopmentOnly;

/**
 * 테스트 발송 (FR-NOTI-15). 개발·스테이징 전용 — 운영 프로파일에는 등록되지 않는다(legacy 01-#21, 05 확인 필요 4).
 */
@RestController
@DevelopmentOnly
@ConditionalOnProperty(name = "notification.test.enabled", havingValue = "true")
class TestSendController {

	/** @param neighborhoodId 생략하면 기기의 현재 동네(회원은 회원의 현재 동네) */
	record TestSendRequest(NotificationKind kind, String neighborhoodId) {
	}

	private final TestSendService testSend;

	TestSendController(TestSendService testSend) {
		this.testSend = testSend;
	}

	@PostMapping("/api/v1/notification/test-sends")
	Dispatcher.Report send(@RequestHeader(DeviceHeaders.DEVICE_ID) String deviceId,
			@RequestHeader(DeviceHeaders.DEVICE_KEY) String deviceKey, @RequestBody TestSendRequest request) {
		if (request.kind() == null) {
			throw new DomainException(ErrorCode.INVALID_REQUEST, "보낼 알림 종류가 필요합니다.");
		}
		return testSend.send(DeviceHeaders.credential(deviceId, deviceKey), request.kind(),
				request.neighborhoodId() == null ? null : new NeighborhoodId(request.neighborhoodId()));
	}

}
