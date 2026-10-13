package kr.weathertalk.notification.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import kr.weathertalk.notification.DevicePlatform;
import kr.weathertalk.notification.DeviceStatus;

/**
 * 발송 기록 (06 §3.4). 발송 키 (종류, 회차 키, 실제 동네 또는 게시글, 기기)가 유일해 다중 인스턴스·이벤트 재전달에도 한 번만 예약된다(BR-NOTI-10).
 * 회원 ID와 본문 원문은 남기지 않는다. payload(제목·본문)는 재시도·보류 동안만 들고 있다가 최종 상태가 되면 지운다.
 */
@Repository
public class DeliveryStore {

	public enum Status {

		RESERVED, SUCCEEDED, RETRY_WAIT, HELD, FAILED, SKIPPED

	}

	public enum SkipReason {

		COOLDOWN, QUIET_HOURS, ACTOR, NEIGHBORHOOD_UNKNOWN, NO_WEATHER, DELAY_EXCEEDED, INVALID_DEVICE,

		/** 푸시 자격(FIREBASE_CREDENTIALS_PATH)이 없어 발송이 꺼져 있음 */
		PUSH_DISABLED,

		/** 재시도 한도·지연 한도를 넘김(최종 실패의 사유) */
		RETRY_EXHAUSTED,

		/** 제공자가 메시지를 거절(최종 실패의 사유) */
		MESSAGE_REJECTED

	}

	/** 재시도·보류 처리용 행. 기기의 지금 토큰·상태를 함께 읽는다. */
	public record Pending(UUID id, NotificationKind kind, String roundKey, String targetKey, UUID deviceId,
			Status status, String templateId, int attempts, String payload, Instant deadline, String pushToken,
			DevicePlatform platform, DeviceStatus deviceStatus) {
	}

	private final JdbcClient jdbc;

	DeliveryStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** 예약. 같은 발송 키가 이미 있으면 빈 값(이미 처리된 회차·이벤트). */
	public Optional<UUID> reserve(NotificationKind kind, String roundKey, String targetKey, UUID deviceId,
			String templateId, Instant deadline, Instant now) {
		return insert(kind, roundKey, targetKey, deviceId, Status.RESERVED, null, templateId, deadline, now);
	}

	/** 발송 시도 없이 생략으로 남긴다(동네 미확인·행위자 본인·날씨 없음·지연 초과 등). 이미 있으면 그대로. */
	public void recordSkip(NotificationKind kind, String roundKey, String targetKey, UUID deviceId, SkipReason reason,
			Instant now) {
		insert(kind, roundKey, targetKey, deviceId, Status.SKIPPED, reason, null, null, now);
	}

	private Optional<UUID> insert(NotificationKind kind, String roundKey, String targetKey, UUID deviceId,
			Status status, SkipReason reason, String templateId, Instant deadline, Instant now) {
		return jdbc.sql("""
				INSERT INTO noti_delivery (id, kind, round_key, target_key, device_id, status, skip_reason, template_id,
				    attempts, deadline_at, created_at, updated_at)
				VALUES (:id, :kind, :round, :target, :device, :status, :reason, :template, 0, :deadline, :now, :now)
				ON CONFLICT (kind, round_key, target_key, device_id) DO NOTHING
				RETURNING id
				""")
			.param("id", UUID.randomUUID())
			.param("kind", kind.name())
			.param("round", roundKey)
			.param("target", targetKey)
			.param("device", deviceId)
			.param("status", status.name())
			.param("reason", reason == null ? null : reason.name())
			.param("template", templateId)
			.param("deadline", deadline == null ? null : Timestamp.from(deadline))
			.param("now", Timestamp.from(now))
			.query(UUID.class)
			.optional();
	}

	public void skip(UUID id, SkipReason reason, Instant now) {
		finish(id, Status.SKIPPED, reason, null, now);
	}

	public void succeed(UUID id, Instant now) {
		jdbc.sql("""
				UPDATE noti_delivery SET status = 'SUCCEEDED', skip_reason = NULL, attempts = attempts + 1, last_response = NULL, payload = NULL,
				    next_attempt_at = NULL, lease_until = NULL, updated_at = :now WHERE id = :id
				""").param("id", id).param("now", Timestamp.from(now)).update();
	}

	public void fail(UUID id, SkipReason reason, String response, Instant now) {
		jdbc.sql("""
				UPDATE noti_delivery SET status = 'FAILED', skip_reason = :reason, attempts = attempts + 1, last_response = :response,
				    payload = NULL, next_attempt_at = NULL, lease_until = NULL, updated_at = :now WHERE id = :id
				""")
			.param("id", id)
			.param("reason", reason.name())
			.param("response", truncate(response))
			.param("now", Timestamp.from(now))
			.update();
	}

	/** 실패 처리지만 시도하지 않은 경우(기기 무효 등) — 시도 횟수를 올리지 않는다. */
	public void abandon(UUID id, SkipReason reason, Instant now) {
		finish(id, Status.FAILED, reason, null, now);
	}

	public void retryLater(UUID id, String response, String payload, Instant nextAttemptAt, Instant now) {
		jdbc.sql("""
				UPDATE noti_delivery SET status = 'RETRY_WAIT', attempts = attempts + 1, last_response = :response,
				    payload = :payload, next_attempt_at = :next, lease_until = NULL, updated_at = :now WHERE id = :id
				""")
			.param("id", id)
			.param("response", truncate(response))
			.param("payload", payload)
			.param("next", Timestamp.from(nextAttemptAt))
			.param("now", Timestamp.from(now))
			.update();
	}

	/** 심야 금지 시간 보류(강수 예보 알림, BR-NOTI-08). deadline = 예상 시작 시각(그때까지 못 보내면 버림). */
	public void hold(UUID id, String payload, Instant releaseAt, Instant deadline, Instant now) {
		jdbc.sql("""
				UPDATE noti_delivery SET status = 'HELD', skip_reason = 'QUIET_HOURS', payload = :payload, next_attempt_at = :next,
				    deadline_at = :deadline, lease_until = NULL, updated_at = :now WHERE id = :id
				""")
			.param("id", id)
			.param("payload", payload)
			.param("next", Timestamp.from(releaseAt))
			.param("deadline", Timestamp.from(deadline))
			.param("now", Timestamp.from(now))
			.update();
	}

	/** 다시 보낼 차례가 된 재시도·보류 건을 점유한다(SKIP LOCKED + 점유 시각 — 인스턴스끼리 같은 건을 나눠 갖지 않게). */
	public List<Pending> claimDue(Instant now, Instant leaseUntil, int limit) {
		return jdbc.sql("""
				WITH due AS (
				    SELECT id FROM noti_delivery
				    WHERE status IN ('RETRY_WAIT', 'HELD') AND next_attempt_at <= :now AND (lease_until IS NULL OR lease_until < :now)
				    ORDER BY next_attempt_at LIMIT :limit FOR UPDATE SKIP LOCKED
				)
				UPDATE noti_delivery d SET lease_until = :lease FROM due WHERE d.id = due.id
				RETURNING d.id, d.kind, d.round_key, d.target_key, d.device_id, d.status, d.template_id, d.attempts, d.payload,
				    d.deadline_at,
				    (SELECT push_token FROM noti_device v WHERE v.id = d.device_id) AS push_token,
				    (SELECT platform FROM noti_device v WHERE v.id = d.device_id) AS platform,
				    (SELECT status FROM noti_device v WHERE v.id = d.device_id) AS device_status
				""")
			.param("now", Timestamp.from(now))
			.param("lease", Timestamp.from(leaseUntil))
			.param("limit", limit)
			.query((rs, n) -> {
				Timestamp deadline = rs.getTimestamp("deadline_at");
				String platform = rs.getString("platform");
				String deviceStatus = rs.getString("device_status");
				return new Pending(rs.getObject("id", UUID.class), NotificationKind.valueOf(rs.getString("kind")),
						rs.getString("round_key"), rs.getString("target_key"), rs.getObject("device_id", UUID.class),
						Status.valueOf(rs.getString("status")), rs.getString("template_id"), rs.getInt("attempts"),
						rs.getString("payload"), deadline == null ? null : deadline.toInstant(),
						rs.getString("push_token"), platform == null ? null : DevicePlatform.valueOf(platform),
						deviceStatus == null ? null : DeviceStatus.valueOf(deviceStatus));
			})
			.list();
	}

	/** 보류 건을 다음 금지 종료 시각으로 다시 미룬다. */
	public void reschedule(UUID id, Instant next, Instant now) {
		jdbc.sql("UPDATE noti_delivery SET next_attempt_at = :next, lease_until = NULL, updated_at = :now WHERE id = :id")
			.param("id", id)
			.param("next", Timestamp.from(next))
			.param("now", Timestamp.from(now))
			.update();
	}

	/** 발송 기록 정리 (FR-NOTI-16). */
	public int deleteCreatedBefore(Instant before) {
		return jdbc.sql("DELETE FROM noti_delivery WHERE created_at < :before AND status NOT IN ('RETRY_WAIT', 'HELD', 'RESERVED')")
			.param("before", Timestamp.from(before))
			.update();
	}

	private void finish(UUID id, Status status, SkipReason reason, String response, Instant now) {
		jdbc.sql("""
				UPDATE noti_delivery SET status = :status, skip_reason = :reason, last_response = COALESCE(:response, last_response),
				    payload = NULL, next_attempt_at = NULL, lease_until = NULL, updated_at = :now WHERE id = :id
				""")
			.param("id", id)
			.param("status", status.name())
			.param("reason", reason == null ? null : reason.name())
			.param("response", response)
			.param("now", Timestamp.from(now))
			.update();
	}

	private static String truncate(String value) {
		if (value == null) {
			return null;
		}
		return value.length() > 60 ? value.substring(0, 60) : value;
	}

}
