package kr.weathertalk.notification;

import kr.weathertalk.shared.Ids.DeviceId;

/**
 * 기기 ID + 기기 키 (06 §2, BR-NOTI-15). 기기 키는 기기 확인 첫 호출에서만 원문으로 받고 앱이 보안 저장소에 둔다.
 * home 조합·알림 구독·외출 시각 요청에 함께 보낸다.
 */
public record DeviceCredential(DeviceId deviceId, String deviceKey) {

	@Override
	public String toString() {
		return "DeviceCredential[deviceId=" + deviceId + ", deviceKey=***]";
	}

}
