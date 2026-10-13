package kr.weathertalk.notification.internal.push;

import java.util.List;

/**
 * 푸시 제공자 포트 (BR-NOTI-11·12). 배치 하나(notification.send.batch-size 이하)를 보내고 건별 결과를 같은 순서로 돌려준다.
 * 한 건의 실패가 다른 건을 막지 않는다 — 예외 대신 건별 결과로 돌려준다.
 */
public interface PushSender {

	/** 자격이 없어 발송이 꺼져 있으면 false(기동은 정상). */
	boolean enabled();

	List<PushResult> send(List<PushMessage> messages);

}
