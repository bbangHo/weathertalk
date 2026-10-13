package kr.weathertalk.notification.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import kr.weathertalk.member.TemperatureSensitivity;
import kr.weathertalk.notification.DevicePlatform;
import kr.weathertalk.notification.DeviceStatus;
import kr.weathertalk.notification.OsNotificationPermission;

/** 기기·외출 시각 저장소 (06 §3.1, noti_device·noti_departure_time). */
@Repository
public class DeviceStore {

	public enum SuspendReason {

		INVALID_TOKEN, STALE

	}

	public record Device(UUID id, String keyHash, DevicePlatform platform, String pushToken, UUID memberId,
			String neighborhoodCode, TemperatureSensitivity guestSensitivity, boolean commentEnabled,
			OsNotificationPermission osPermission, DeviceStatus status, SuspendReason suspendedReason,
			Instant lastConfirmedAt) {

		public boolean linked() {
			return memberId != null;
		}

	}

	private final JdbcClient jdbc;

	DeviceStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<Device> lock(UUID id) {
		return jdbc.sql("SELECT * FROM noti_device WHERE id = :id FOR UPDATE").param("id", id).query(DeviceStore::map)
			.optional();
	}

	public Optional<Device> find(UUID id) {
		return jdbc.sql("SELECT * FROM noti_device WHERE id = :id").param("id", id).query(DeviceStore::map).optional();
	}

	public List<UUID> idsWithToken(String pushToken, UUID except) {
		return jdbc.sql("SELECT id FROM noti_device WHERE push_token = :token AND id <> :except FOR UPDATE")
			.param("token", pushToken)
			.param("except", except)
			.query(UUID.class)
			.list();
	}

	public List<UUID> idsOfMember(UUID memberId) {
		return jdbc.sql("SELECT id FROM noti_device WHERE member_id = :member ORDER BY id")
			.param("member", memberId)
			.query(UUID.class)
			.list();
	}

	public void insert(UUID id, String keyHash, DevicePlatform platform, String pushToken, UUID memberId,
			OsNotificationPermission permission, boolean commentEnabled, Instant now) {
		jdbc.sql("""
				INSERT INTO noti_device (id, key_hash, platform, push_token, member_id, guest_sensitivity, comment_enabled,
				    os_permission, status, registered_at, last_confirmed_at)
				VALUES (:id, :keyHash, :platform, :token, :member, 'NORMAL', :comment, :permission, 'ACTIVE', :now, :now)
				""")
			.param("id", id)
			.param("keyHash", keyHash)
			.param("platform", platform.name())
			.param("token", pushToken)
			.param("member", memberId)
			.param("comment", commentEnabled)
			.param("permission", permission.name())
			.param("now", Timestamp.from(now))
			.update();
	}

	public void touch(UUID id, DevicePlatform platform, OsNotificationPermission permission, Instant now) {
		jdbc.sql("""
				UPDATE noti_device SET last_confirmed_at = :now,
				    platform = COALESCE(:platform, platform), os_permission = COALESCE(:permission, os_permission)
				WHERE id = :id
				""")
			.param("id", id)
			.param("platform", platform == null ? null : platform.name())
			.param("permission", permission == null ? null : permission.name())
			.param("now", Timestamp.from(now))
			.update();
	}

	public void updateToken(UUID id, String pushToken) {
		jdbc.sql("UPDATE noti_device SET push_token = :token WHERE id = :id").param("id", id).param("token", pushToken)
			.update();
	}

	public void activate(UUID id) {
		jdbc.sql("UPDATE noti_device SET status = 'ACTIVE', suspended_reason = NULL, suspended_at = NULL WHERE id = :id")
			.param("id", id)
			.update();
	}

	/** 발송 중지. 무효 토큰이면 그 토큰일 때만(그사이 새 토큰으로 바뀌었으면 그대로 둔다). */
	public int suspend(UUID id, SuspendReason reason, String ifToken, Instant now) {
		return jdbc.sql("""
				UPDATE noti_device SET status = 'SUSPENDED', suspended_reason = :reason, suspended_at = :now
				WHERE id = :id AND status = 'ACTIVE' AND (CAST(:token AS varchar) IS NULL OR push_token = :token)
				""")
			.param("id", id)
			.param("reason", reason.name())
			.param("token", ifToken)
			.param("now", Timestamp.from(now))
			.update();
	}

	public void link(UUID id, UUID memberId, boolean commentEnabled) {
		jdbc.sql("UPDATE noti_device SET member_id = :member, comment_enabled = :comment WHERE id = :id")
			.param("id", id)
			.param("member", memberId)
			.param("comment", commentEnabled)
			.update();
	}

	public void setNeighborhood(UUID id, String code) {
		jdbc.sql("UPDATE noti_device SET neighborhood_code = :code WHERE id = :id").param("id", id).param("code", code)
			.update();
	}

	public void setGuestSensitivity(UUID id, TemperatureSensitivity sensitivity) {
		jdbc.sql("UPDATE noti_device SET guest_sensitivity = :s WHERE id = :id")
			.param("id", id)
			.param("s", sensitivity.name())
			.update();
	}

	public void setCommentEnabled(UUID id, boolean enabled) {
		jdbc.sql("UPDATE noti_device SET comment_enabled = :c WHERE id = :id").param("id", id).param("c", enabled)
			.update();
	}

	public void delete(UUID id) {
		jdbc.sql("DELETE FROM noti_device WHERE id = :id").param("id", id).update();
	}

	public List<LocalTime> departureTimes(UUID id) {
		return jdbc.sql("SELECT departure_time FROM noti_departure_time WHERE device_id = :id ORDER BY departure_time")
			.param("id", id)
			.query((rs, n) -> rs.getObject(1, LocalTime.class))
			.list();
	}

	public void replaceDepartureTimes(UUID id, List<LocalTime> times) {
		jdbc.sql("DELETE FROM noti_departure_time WHERE device_id = :id").param("id", id).update();
		for (LocalTime time : times) {
			jdbc.sql("INSERT INTO noti_departure_time (device_id, departure_time) VALUES (:id, :time)")
				.param("id", id)
				.param("time", time)
				.update();
		}
	}

	/** 마지막 확인이 오래된 활성 기기 (FR-NOTI-13). */
	public int suspendStale(Instant lastConfirmedBefore, Instant now) {
		return jdbc.sql("""
				UPDATE noti_device SET status = 'SUSPENDED', suspended_reason = 'STALE', suspended_at = :now
				WHERE status = 'ACTIVE' AND last_confirmed_at < :before
				""").param("before", Timestamp.from(lastConfirmedBefore)).param("now", Timestamp.from(now)).update();
	}

	public List<UUID> suspendedBefore(Instant before) {
		return jdbc.sql("SELECT id FROM noti_device WHERE status = 'SUSPENDED' AND suspended_at < :before ORDER BY id")
			.param("before", Timestamp.from(before))
			.query(UUID.class)
			.list();
	}

	private static Device map(ResultSet rs, int row) throws SQLException {
		String reason = rs.getString("suspended_reason");
		return new Device(rs.getObject("id", UUID.class), rs.getString("key_hash"),
				DevicePlatform.valueOf(rs.getString("platform")), rs.getString("push_token"),
				rs.getObject("member_id", UUID.class), rs.getString("neighborhood_code"),
				TemperatureSensitivity.valueOf(rs.getString("guest_sensitivity")), rs.getBoolean("comment_enabled"),
				OsNotificationPermission.valueOf(rs.getString("os_permission")),
				DeviceStatus.valueOf(rs.getString("status")), reason == null ? null : SuspendReason.valueOf(reason),
				rs.getTimestamp("last_confirmed_at").toInstant());
	}

}
