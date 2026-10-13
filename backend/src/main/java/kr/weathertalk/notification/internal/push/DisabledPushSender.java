package kr.weathertalk.notification.internal.push;

import java.util.List;

/** 자격(FIREBASE_CREDENTIALS_PATH)이 없을 때의 발송기: 아무것도 보내지 않는다. 발송 기록은 '생략(발송 비활성)'이 된다. */
final class DisabledPushSender implements PushSender {

	@Override
	public boolean enabled() {
		return false;
	}

	@Override
	public List<PushResult> send(List<PushMessage> messages) {
		return messages.stream().map(m -> PushResult.transientFailure("PUSH_DISABLED")).toList();
	}

}
