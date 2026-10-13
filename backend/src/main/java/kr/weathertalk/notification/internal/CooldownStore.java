package kr.weathertalk.notification.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 쿨다운 (BR-NOTI-07): 날씨 이벤트 알림은 (종류, 실제 동네, 기기), 댓글 알림은 (종류, 게시글, 기기).
 * <p>
 * 확인과 기록을 upsert 한 문장으로 한다(선점) — 만료된 행만 덮어쓰므로 동시에 들어온 두 처리 중 하나만 이긴다(legacy 05-#7).
 * 발송이 성공하면 그 시각부터 쿨다운을 확정하고, 최종 실패·생략이면 선점을 푼다(실패에도 쿨다운이 걸리던 문제, legacy 05 확인 필요 9).
 */
@Repository
public class CooldownStore {

	private final JdbcClient jdbc;

	CooldownStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** @return 선점했으면 참. 쿨다운 중이면 거짓 */
	public boolean claim(NotificationKind kind, String targetKey, UUID deviceId, UUID holder, Instant now,
			Instant until) {
		return jdbc.sql("""
				INSERT INTO noti_cooldown (kind, target_key, device_id, until, holder)
				VALUES (:kind, :target, :device, :until, :holder)
				ON CONFLICT (kind, target_key, device_id) DO UPDATE SET until = EXCLUDED.until, holder = EXCLUDED.holder
				WHERE noti_cooldown.until <= :now
				RETURNING holder
				""")
			.param("kind", kind.name())
			.param("target", targetKey)
			.param("device", deviceId)
			.param("until", Timestamp.from(until))
			.param("holder", holder)
			.param("now", Timestamp.from(now))
			.query(UUID.class)
			.optional()
			.isPresent();
	}

	/** 발송 성공: 성공 시각부터 쿨다운 기간을 건다. */
	public void confirm(UUID holder, Instant until) {
		jdbc.sql("UPDATE noti_cooldown SET until = :until WHERE holder = :holder")
			.param("holder", holder)
			.param("until", Timestamp.from(until))
			.update();
	}

	/** 최종 실패·생략: 선점을 푼다(이 발송이 잡은 것만). */
	public void release(UUID holder) {
		jdbc.sql("DELETE FROM noti_cooldown WHERE holder = :holder").param("holder", holder).update();
	}

	public void deleteExpired(Instant now) {
		jdbc.sql("DELETE FROM noti_cooldown WHERE until < :now").param("now", Timestamp.from(now)).update();
	}

}
