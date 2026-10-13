package kr.weathertalk.notification;

import kr.weathertalk.shared.Ids.DeviceId;

/**
 * 기기 확인 결과 (contracts.md §2.7).
 *
 * @param deviceKey 새로 등록했을 때만 원문(이 응답에서만 제공). 그 밖에는 null
 * @param registered 이번 호출로 새 기기를 등록했는가
 * @param linkedToMember 대조 후 회원과 연결돼 있는가
 * @param commentAlertEnabled 기기 단위 댓글 알림(회원 연결 중에만 의미)
 */
public record DeviceConfirmation(DeviceId deviceId, String deviceKey, boolean registered, DeviceStatus status,
		boolean linkedToMember, boolean commentAlertEnabled) {

	@Override
	public String toString() {
		return "DeviceConfirmation[deviceId=" + deviceId + ", registered=" + registered + ", status=" + status
				+ ", linkedToMember=" + linkedToMember + "]";
	}

}
