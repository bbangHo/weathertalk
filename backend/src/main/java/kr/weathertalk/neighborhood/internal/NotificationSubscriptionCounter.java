package kr.weathertalk.neighborhood.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import kr.weathertalk.shared.Ids.NeighborhoodId;
import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;

/**
 * 알림 구독 동네 반영 (FR-NBR-10, BR-NBR-09). 동네별 알림 구독 수를 활성 사유로 보관한다. 구독의 종류·기기는 모른다.
 * 호출자(notification)의 트랜잭션에 함께 묶인다(구독 변경이 롤백되면 증감도 롤백).
 */
@Component
class NotificationSubscriptionCounter {

	private static final Logger log = LoggerFactory.getLogger(NotificationSubscriptionCounter.class);

	private final JdbcClient jdbc;
	private final Clock clock;

	NotificationSubscriptionCounter(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Transactional
	public void apply(NeighborhoodId id, int delta) {
		if (delta == 0) {
			return;
		}
		boolean exists = jdbc.sql("SELECT count(*) FROM nbr_neighborhood WHERE code = :code")
			.param("code", id.value())
			.query(Integer.class)
			.single() > 0;
		if (!exists) {
			throw new DomainException(ErrorCode.NOT_FOUND, "없는 동네입니다.");
		}
		Timestamp now = Timestamp.from(clock.instant());
		// 0 아래로는 내려가지 않는다. 어긋남은 notification의 대조 배치가 바로잡는다.
		int previous = jdbc
			.sql("SELECT subscription_count FROM nbr_notification_subscription WHERE neighborhood_code = :code FOR UPDATE")
			.param("code", id.value())
			.query(Integer.class)
			.optional()
			.orElse(0);
		jdbc.sql("""
				INSERT INTO nbr_notification_subscription (neighborhood_code, subscription_count, updated_at)
				VALUES (:code, GREATEST(0, :delta), :now)
				ON CONFLICT (neighborhood_code) DO UPDATE
				SET subscription_count = GREATEST(0, nbr_notification_subscription.subscription_count + :delta), updated_at = :now
				""").param("code", id.value()).param("delta", delta).param("now", now).update();
		if (previous + delta < 0) {
			log.warn("알림 구독 수가 0 아래로 내려가려 해 0에서 멈춤: neighborhood={}, before={}, delta={}", id.value(), previous,
					delta);
		}
	}

	@Transactional
	public int reconcile(Map<NeighborhoodId, Integer> counts) {
		Map<String, Integer> current = new HashMap<>();
		jdbc.sql("SELECT neighborhood_code, subscription_count FROM nbr_notification_subscription")
			.query((rs, n) -> current.put(rs.getString(1), rs.getInt(2)))
			.list();
		Map<String, Integer> target = new HashMap<>();
		counts.forEach((id, count) -> {
			if (count != null && count > 0) {
				target.put(id.value(), count);
			}
		});
		Timestamp now = Timestamp.from(clock.instant());
		int changed = 0;
		for (Map.Entry<String, Integer> entry : current.entrySet()) {
			if (!target.containsKey(entry.getKey()) && entry.getValue() != 0) {
				jdbc.sql("UPDATE nbr_notification_subscription SET subscription_count = 0, updated_at = :now WHERE neighborhood_code = :code")
					.param("code", entry.getKey())
					.param("now", now)
					.update();
				changed++;
			}
		}
		for (Map.Entry<String, Integer> entry : target.entrySet()) {
			if (!entry.getValue().equals(current.get(entry.getKey()))) {
				jdbc.sql("""
						INSERT INTO nbr_notification_subscription (neighborhood_code, subscription_count, updated_at)
						SELECT code, :count, :now FROM nbr_neighborhood WHERE code = :code
						ON CONFLICT (neighborhood_code) DO UPDATE SET subscription_count = :count, updated_at = :now
						""").param("code", entry.getKey()).param("count", entry.getValue()).param("now", now).update();
				changed++;
			}
		}
		if (changed > 0) {
			log.info("알림 구독 동네 대조: {}개 동네의 구독 수를 바로잡음", changed);
		}
		return changed;
	}

}
