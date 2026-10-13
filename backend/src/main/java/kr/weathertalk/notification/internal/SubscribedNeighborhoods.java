package kr.weathertalk.notification.internal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import kr.weathertalk.neighborhood.NeighborhoodApi;
import kr.weathertalk.shared.Ids.NeighborhoodId;

/**
 * 알림 구독 동네 반영 (FR-NOTI-05·07·13·14 → neighborhood FR-NBR-10).
 * <p>
 * 기기 하나를 바꾸는 모든 일(구독 생성·변경·삭제, 기기 현재 동네 보고, 회원 연결·해제, 기기 삭제)을 {@link #track}으로 감싸
 * 바꾸기 전·후의 반영 대상 동네를 비교하고 차이만 neighborhood에 증감으로 알린다. 경로마다 증감을 따로 계산하지 않아 누락이 없고,
 * 같은 트랜잭션에 묶여 구독 변경이 롤백되면 증감도 롤백된다. 그래도 생길 수 있는 어긋남은 매일 {@link #reconcile}로 바로잡는다.
 */
@Component
public class SubscribedNeighborhoods {

	private final SubscriptionStore subscriptions;
	private final NeighborhoodApi neighborhoodApi;

	SubscribedNeighborhoods(SubscriptionStore subscriptions, NeighborhoodApi neighborhoodApi) {
		this.subscriptions = subscriptions;
		this.neighborhoodApi = neighborhoodApi;
	}

	/** 호출자 트랜잭션 안에서, 기기 행을 잠근 뒤에 부른다. */
	public <T> T track(UUID deviceId, Supplier<T> change) {
		List<String> before = subscriptions.contributions(deviceId);
		T result = change.get();
		List<String> after = subscriptions.contributions(deviceId);
		Map<String, Integer> delta = new HashMap<>();
		before.forEach(code -> delta.merge(code, -1, Integer::sum));
		after.forEach(code -> delta.merge(code, 1, Integer::sum));
		delta.forEach((code, d) -> {
			if (d != 0) {
				neighborhoodApi.applyNotificationSubscriptionChange(new NeighborhoodId(code), d);
			}
		});
		return result;
	}

	public void trackRun(UUID deviceId, Runnable change) {
		track(deviceId, () -> {
			change.run();
			return null;
		});
	}

	/** 대조 배치(02 §9 미결 8): notification이 진실 원천. @return 바로잡은 동네 수 */
	@Transactional
	public int reconcile() {
		Map<NeighborhoodId, Integer> totals = subscriptions.contributionTotals()
			.entrySet()
			.stream()
			.collect(Collectors.toMap(e -> new NeighborhoodId(e.getKey()), Map.Entry::getValue));
		return neighborhoodApi.reconcileNotificationSubscriptions(totals);
	}

}
