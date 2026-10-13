package kr.weathertalk.notification;

import kr.weathertalk.shared.Ids.DeviceId;

/**
 * 기기 확인 입력 (contracts.md §2.7, FR-NOTI-01·02).
 *
 * @param deviceId 첫 호출(등록)이면 null
 * @param deviceKey 첫 호출이면 null
 * @param platform 등록 때 필수
 * @param pushToken 권한 요청 전이면 없을 수 있다
 * @param osPermission 없으면 바꾸지 않는다(등록 때는 UNKNOWN)
 */
public record DeviceConfirmCommand(DeviceId deviceId, String deviceKey, DevicePlatform platform, String pushToken,
		OsNotificationPermission osPermission) {

	@Override
	public String toString() {
		return "DeviceConfirmCommand[deviceId=" + deviceId + ", platform=" + platform + ", osPermission=" + osPermission
				+ "]";
	}

}
