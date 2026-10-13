package kr.weathertalk.notification.internal.push;

import java.util.Map;

import kr.weathertalk.notification.DevicePlatform;

/**
 * 푸시 1건: 표시용 제목·본문 + 데이터(알림 종류, 동네 ID 또는 게시글 ID, 이동 대상 키, 발송 키) + 묶음 키(같은 알림이 트레이에 쌓이지 않게, 06 §4.1).
 */
public record PushMessage(String token, DevicePlatform platform, String title, String body, Map<String, String> data,
		String collapseKey) {

	@Override
	public String toString() {
		return "PushMessage[platform=" + platform + ", collapseKey=" + collapseKey + "]";
	}

}
