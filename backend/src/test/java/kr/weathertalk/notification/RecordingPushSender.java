package kr.weathertalk.notification;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import kr.weathertalk.notification.internal.push.PushMessage;
import kr.weathertalk.notification.internal.push.PushResult;
import kr.weathertalk.notification.internal.push.PushSender;

/**
 * 통합 테스트용 푸시 발송기: 보낸 메시지를 모으고, 토큰별로 정해 둔 결과(없으면 성공)를 돌려준다. 여러 인스턴스(컨텍스트)가 하나를 공유할 수 있다.
 * FCM HTTP 어댑터 자체는 FcmPushSenderTests가 WireMock으로 검증한다.
 */
public class RecordingPushSender implements PushSender {

	private final Queue<PushMessage> sent = new ConcurrentLinkedQueue<>();
	private final Map<String, Queue<PushResult>> scripted = new ConcurrentHashMap<>();
	private volatile boolean enabled = true;

	@Override
	public boolean enabled() {
		return enabled;
	}

	@Override
	public List<PushResult> send(List<PushMessage> messages) {
		List<PushResult> results = new ArrayList<>();
		for (PushMessage message : messages) {
			Queue<PushResult> queue = scripted.get(message.token());
			PushResult result = queue == null ? null : queue.poll();
			if (result == null || result.outcome() == PushResult.Outcome.SUCCESS) {
				sent.add(message);
			}
			results.add(result == null ? PushResult.success() : result);
		}
		return results;
	}

	/** 이 토큰으로 보낼 때 차례로 돌려줄 결과. 다 쓰면 성공. */
	public void script(String token, PushResult... results) {
		scripted.computeIfAbsent(token, k -> new ConcurrentLinkedQueue<>()).addAll(List.of(results));
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	/** 성공으로 처리된 메시지. */
	public List<PushMessage> sent() {
		return List.copyOf(sent);
	}

	public List<PushMessage> sentTo(String token) {
		return sent.stream().filter(m -> m.token().equals(token)).toList();
	}

	public void clear() {
		sent.clear();
		scripted.clear();
		enabled = true;
	}

}
