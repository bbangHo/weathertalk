package kr.weathertalk.notification.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 동네별 알림 구독·정기 알림 시각 저장소 (06 §3.2) + 알림 구독 동네 집계(FR-NBR-10 반영 대상).
 */
@Repository
public class SubscriptionStore {

	public enum TargetType {

		/** 현재 동네 따라가기: 회원 연결 기기는 회원의 현재 동네, 비회원 기기는 기기 현재 동네 */
		FOLLOW_CURRENT,

		/** 지정 동네 */
		FIXED

	}

	public record Subscription(UUID id, UUID deviceId, TargetType targetType, String neighborhoodCode,
			boolean dailyEnabled, List<LocalTime> dailyTimes, boolean precipitationForecastEnabled,
			boolean fieldReportEnabled, Instant consentedAt) {
	}

	private final JdbcClient jdbc;

	SubscriptionStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public List<Subscription> ofDevice(UUID deviceId) {
		Map<UUID, List<LocalTime>> times = new LinkedHashMap<>();
		jdbc.sql("""
				SELECT t.subscription_id, t.alert_time FROM noti_daily_time t
				JOIN noti_subscription s ON s.id = t.subscription_id WHERE s.device_id = :device ORDER BY t.alert_time
				""").param("device", deviceId).query((rs, n) -> {
			times.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>())
				.add(rs.getObject(2, LocalTime.class));
			return null;
		}).list();
		return jdbc
			.sql("SELECT * FROM noti_subscription WHERE device_id = :device ORDER BY target_type DESC, neighborhood_code, created_at")
			.param("device", deviceId)
			.query((rs, n) -> map(rs, times))
			.list();
	}

	public Optional<Subscription> find(UUID deviceId, TargetType type, String code) {
		return ofDevice(deviceId).stream()
			.filter(s -> s.targetType() == type
					&& (type == TargetType.FOLLOW_CURRENT || s.neighborhoodCode().equals(code)))
			.findFirst();
	}

	public void insert(UUID id, UUID deviceId, TargetType type, String code, boolean daily, boolean precipitation,
			boolean fieldReport, Instant consentedAt, Instant now) {
		jdbc.sql("""
				INSERT INTO noti_subscription (id, device_id, target_type, neighborhood_code, daily_enabled,
				    precipitation_forecast_enabled, field_report_enabled, consented_at, created_at, updated_at)
				VALUES (:id, :device, :type, :code, :daily, :precip, :field, :consented, :now, :now)
				""")
			.param("id", id)
			.param("device", deviceId)
			.param("type", type.name())
			.param("code", code)
			.param("daily", daily)
			.param("precip", precipitation)
			.param("field", fieldReport)
			.param("consented", consentedAt == null ? null : Timestamp.from(consentedAt))
			.param("now", Timestamp.from(now))
			.update();
	}

	public void update(UUID id, boolean daily, boolean precipitation, boolean fieldReport, Instant consentedAt,
			Instant now) {
		jdbc.sql("""
				UPDATE noti_subscription SET daily_enabled = :daily, precipitation_forecast_enabled = :precip,
				    field_report_enabled = :field, consented_at = :consented, updated_at = :now WHERE id = :id
				""")
			.param("id", id)
			.param("daily", daily)
			.param("precip", precipitation)
			.param("field", fieldReport)
			.param("consented", consentedAt == null ? null : Timestamp.from(consentedAt))
			.param("now", Timestamp.from(now))
			.update();
	}

	public void replaceDailyTimes(UUID subscriptionId, List<LocalTime> times) {
		jdbc.sql("DELETE FROM noti_daily_time WHERE subscription_id = :id").param("id", subscriptionId).update();
		for (LocalTime time : times) {
			jdbc.sql("INSERT INTO noti_daily_time (subscription_id, alert_time) VALUES (:id, :time)")
				.param("id", subscriptionId)
				.param("time", time)
				.update();
		}
	}

	public void delete(UUID id) {
		jdbc.sql("DELETE FROM noti_subscription WHERE id = :id").param("id", id).update();
	}

	/**
	 * 기기의 알림 구독 동네 반영 대상(FR-NOTI-07): 지정 동네 구독 → 그 동네, 비회원 기기의 현재 동네 따라가기 → 기기 현재 동네.
	 * 회원 연결 기기의 현재 동네 따라가기는 회원 활동으로 이미 활성 동네라 세지 않는다.
	 */
	public List<String> contributions(UUID deviceId) {
		return jdbc.sql(CONTRIBUTIONS + " AND s.device_id = :device").param("device", deviceId).query(String.class)
			.list()
			.stream()
			.filter(code -> code != null)
			.toList();
	}

	/** 동네별 알림 구독 수 전체(대조 배치 — notification이 진실 원천). */
	public Map<String, Integer> contributionTotals() {
		Map<String, Integer> totals = new LinkedHashMap<>();
		jdbc.sql("SELECT code, count(*) FROM (" + CONTRIBUTIONS + ") c WHERE code IS NOT NULL GROUP BY code")
			.query((rs, n) -> totals.put(rs.getString(1), rs.getInt(2)))
			.list();
		return totals;
	}

	private static final String CONTRIBUTIONS = """
			SELECT CASE WHEN s.target_type = 'FIXED' THEN s.neighborhood_code
			            WHEN d.member_id IS NULL THEN d.neighborhood_code END AS code
			FROM noti_subscription s JOIN noti_device d ON d.id = s.device_id WHERE 1 = 1""";

	private static Subscription map(ResultSet rs, Map<UUID, List<LocalTime>> times) throws SQLException {
		UUID id = rs.getObject("id", UUID.class);
		Timestamp consented = rs.getTimestamp("consented_at");
		return new Subscription(id, rs.getObject("device_id", UUID.class), TargetType.valueOf(rs.getString("target_type")),
				rs.getString("neighborhood_code"), rs.getBoolean("daily_enabled"), times.getOrDefault(id, List.of()),
				rs.getBoolean("precipitation_forecast_enabled"), rs.getBoolean("field_report_enabled"),
				consented == null ? null : consented.toInstant());
	}

}
