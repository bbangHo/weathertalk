package kr.weathertalk.notification.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import kr.weathertalk.member.MemberApi;
import kr.weathertalk.member.MemberProfile;
import kr.weathertalk.member.TemperatureSensitivity;
import kr.weathertalk.neighborhood.CurrentNeighborhood;
import kr.weathertalk.neighborhood.NeighborhoodApi;
import kr.weathertalk.notification.DevicePlatform;
import kr.weathertalk.notification.internal.SubscriptionStore.TargetType;
import kr.weathertalk.shared.Ids.MemberId;

/**
 * 발송 대상 선정 (BR-NOTI-16): 활성 기기 + OS 알림 권한 '거부' 아님 + 푸시 토큰 있음 + 해당 종류 on.
 * 구독 동네와 온도 민감도는 저장 시점이 아니라 발송 시점에 해석한다(BR-NOTI-06): 회원 연결 기기는 회원의 현재 동네·프로필 민감도,
 * 비회원 기기는 기기 현재 동네·알림용 민감도. 같은 기기에서 같은 동네가 두 구독으로 겹치면 한 번만 보낸다(§3.2).
 */
@Component
public class Subscribers {

	public record Row(UUID deviceId, TargetType targetType, String fixedCode, UUID memberId, String deviceCode,
			TemperatureSensitivity guestSensitivity, String pushToken, DevicePlatform platform) {
	}

	/**
	 * @param neighborhoodCode 해석된 동네. 모르면 null(동네 미확인)
	 */
	public record Resolved(UUID deviceId, UUID memberId, String neighborhoodCode, TemperatureSensitivity sensitivity,
			String pushToken, DevicePlatform platform) {

		public Dispatcher.Recipient recipient() {
			return new Dispatcher.Recipient(deviceId, pushToken, platform);
		}

	}

	public enum EventColumn {

		PRECIPITATION_FORECAST("precipitation_forecast_enabled"), FIELD_REPORT("field_report_enabled");

		private final String column;

		EventColumn(String column) {
			this.column = column;
		}

	}

	private static final String SELECT = """
			SELECT s.device_id, s.target_type, s.neighborhood_code AS fixed_code, d.member_id,
			       d.neighborhood_code AS device_code, d.guest_sensitivity, d.push_token, d.platform
			FROM noti_subscription s JOIN noti_device d ON d.id = s.device_id
			WHERE d.status = 'ACTIVE' AND d.os_permission <> 'DENIED' AND d.push_token IS NOT NULL
			""";

	private final JdbcClient jdbc;
	private final NeighborhoodApi neighborhoodApi;
	private final MemberApi memberApi;

	Subscribers(JdbcClient jdbc, NeighborhoodApi neighborhoodApi, MemberApi memberApi) {
		this.jdbc = jdbc;
		this.neighborhoodApi = neighborhoodApi;
		this.memberApi = memberApi;
	}

	/** 정기 날씨 알림 on이고 이 시각을 포함하는 구독. */
	public List<Row> daily(LocalTime time) {
		return jdbc.sql(SELECT + """
				 AND s.daily_enabled AND EXISTS (SELECT 1 FROM noti_daily_time t WHERE t.subscription_id = s.id AND t.alert_time = :time)
				ORDER BY s.device_id
				""").param("time", time).query(Subscribers::map).list();
	}

	/** 그 종류가 on이고 해석된 동네가 이 동네일 수 있는 구독(회원의 현재 동네 따라가기는 해석 후 거른다). */
	public List<Row> forNeighborhood(EventColumn column, String code) {
		return jdbc.sql(SELECT + " AND s." + column.column + """
				 AND ((s.target_type = 'FIXED' AND s.neighborhood_code = :code)
				   OR (s.target_type = 'FOLLOW_CURRENT' AND (d.member_id IS NOT NULL OR d.neighborhood_code = :code)))
				ORDER BY s.device_id
				""").param("code", code).query(Subscribers::map).list();
	}

	/** 행을 해석하고 (기기, 동네) 중복을 없앤다. */
	public List<Resolved> resolve(List<Row> rows) {
		Set<MemberId> members = rows.stream()
			.map(Row::memberId)
			.filter(m -> m != null)
			.map(MemberId::new)
			.collect(Collectors.toSet());
		Map<MemberId, CurrentNeighborhood> currents = members.isEmpty() ? Map.of()
				: neighborhoodApi.findCurrentNeighborhoods(members);
		Map<MemberId, MemberProfile> profiles = members.isEmpty() ? Map.of() : memberApi.findProfiles(members);
		Map<String, Resolved> unique = new LinkedHashMap<>();
		List<Resolved> unresolved = new ArrayList<>();
		for (Row row : rows) {
			MemberId member = row.memberId() == null ? null : new MemberId(row.memberId());
			String code;
			if (row.targetType() == TargetType.FIXED) {
				code = row.fixedCode();
			}
			else if (member != null) {
				CurrentNeighborhood current = currents.get(member);
				code = current == null ? null : current.neighborhood().id().value();
			}
			else {
				code = row.deviceCode();
			}
			TemperatureSensitivity sensitivity = row.guestSensitivity();
			if (member != null) {
				MemberProfile profile = profiles.get(member);
				if (profile != null && !profile.withdrawn() && profile.temperatureSensitivity() != null) {
					sensitivity = profile.temperatureSensitivity();
				}
			}
			Resolved resolved = new Resolved(row.deviceId(), row.memberId(), code,
					sensitivity == null ? TemperatureSensitivity.NORMAL : sensitivity, row.pushToken(), row.platform());
			if (code == null) {
				unresolved.add(resolved);
			}
			else {
				unique.putIfAbsent(row.deviceId() + "|" + code, resolved);
			}
		}
		List<Resolved> result = new ArrayList<>(unique.values());
		unresolved.stream()
			.filter(u -> result.stream().noneMatch(r -> r.deviceId().equals(u.deviceId())))
			.collect(Collectors.toMap(Resolved::deviceId, r -> r, (a, b) -> a, LinkedHashMap::new))
			.values()
			.forEach(result::add);
		return result;
	}

	/** 댓글 알림 대상: 수신 회원에 연결된 활성 기기 중 댓글 알림 on (내부 '회원의 기기 목록', 06 §6.1). */
	public List<Resolved> commentDevices(Collection<UUID> memberIds) {
		if (memberIds.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("""
				SELECT id, member_id, push_token, platform FROM noti_device
				WHERE member_id IN (:members) AND comment_enabled AND status = 'ACTIVE' AND os_permission <> 'DENIED'
				  AND push_token IS NOT NULL
				ORDER BY id
				""")
			.param("members", memberIds)
			.query((rs, n) -> new Resolved(rs.getObject("id", UUID.class), rs.getObject("member_id", UUID.class), null,
					null, rs.getString("push_token"), DevicePlatform.valueOf(rs.getString("platform"))))
			.list();
	}

	private static Row map(ResultSet rs, int n) throws SQLException {
		return new Row(rs.getObject("device_id", UUID.class), TargetType.valueOf(rs.getString("target_type")),
				rs.getString("fixed_code"), rs.getObject("member_id", UUID.class), rs.getString("device_code"),
				TemperatureSensitivity.valueOf(rs.getString("guest_sensitivity")), rs.getString("push_token"),
				DevicePlatform.valueOf(rs.getString("platform")));
	}

}
