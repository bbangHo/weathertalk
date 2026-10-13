package kr.weathertalk.notification.internal.web;

import java.util.UUID;

import kr.weathertalk.notification.DeviceCredential;
import kr.weathertalk.shared.Ids.DeviceId;
import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;

/** 기기 소유 증명 헤더 (BR-NOTI-15). 기기 키는 비밀값이라 주소(쿼리)에 싣지 않는다. */
final class DeviceHeaders {

	static final String DEVICE_ID = "X-Device-Id";
	static final String DEVICE_KEY = "X-Device-Key";

	private DeviceHeaders() {
	}

	static DeviceCredential credential(String deviceId, String deviceKey) {
		if (deviceId == null || deviceId.isBlank() || deviceKey == null || deviceKey.isBlank()) {
			throw new DomainException(ErrorCode.INVALID_REQUEST, "기기 ID와 기기 키가 필요합니다.");
		}
		try {
			return new DeviceCredential(new DeviceId(UUID.fromString(deviceId.strip())), deviceKey.strip());
		}
		catch (IllegalArgumentException e) {
			throw new DomainException(ErrorCode.INVALID_REQUEST, "기기 ID 형식이 맞지 않습니다.");
		}
	}

}
