package kr.weathertalk.feeling.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import kr.weathertalk.feeling.CalibrationOffer;
import kr.weathertalk.feeling.CalibrationOfferStatus;
import kr.weathertalk.member.TemperatureSensitivity;

/**
 * 민감도 보정 제안 저장소 (feeling_calibration_offer)와 회원별 빈도 제한 기준 시각 (feeling_calibration_member).
 * 회원당 '제안됨'은 부분 고유 색인으로 최대 하나다(불변식 7).
 */
@Repository
class CalibrationOfferRepository {

	/** 빈도 제한 기준 시각 (BR-FEL-17 규칙 6). 기록이 없으면 null. */
	record MemberState(Instant lastJudgedAt, Instant lastDeclinedAt, Instant lastSensitivityChangedAt) {

		static final MemberState EMPTY = new MemberState(null, null, null);

	}

	record StoredOffer(UUID id, UUID memberId, CalibrationOffer offer, int mappingVersion) {
	}

	private static final String COLUMNS = """
			id, member_id, current_sensitivity, suggested_sensitivity, record_count, weighted_record_count,
			error_heat_sensitive, error_normal, error_cold_sensitive, mapping_version, judged_at, expires_at, status
			""";

	private final JdbcClient jdbc;

	CalibrationOfferRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** @return 넣었으면 true. 이미 '제안됨'이 있으면 넣지 않는다 */
	boolean insertOffered(UUID memberId, CalibrationOffer offer, int mappingVersion) {
		Map<TemperatureSensitivity, Double> e = offer.errors();
		return jdbc.sql("INSERT INTO feeling_calibration_offer (" + COLUMNS + ") VALUES (:id, :member, :current, "
				+ ":suggested, :count, :weighted, :eHeat, :eNormal, :eCold, :version, :judged, :expires, 'OFFERED') "
				+ "ON CONFLICT (member_id) WHERE status = 'OFFERED' DO NOTHING")
			.param("id", offer.offerId())
			.param("member", memberId)
			.param("current", offer.currentGroup().name())
			.param("suggested", offer.suggestedGroup().name())
			.param("count", offer.recordCount())
			.param("weighted", offer.weightedRecordCount())
			.param("eHeat", e.get(TemperatureSensitivity.HEAT_SENSITIVE))
			.param("eNormal", e.get(TemperatureSensitivity.NORMAL))
			.param("eCold", e.get(TemperatureSensitivity.COLD_SENSITIVE))
			.param("version", mappingVersion)
			.param("judged", Timestamp.from(offer.judgedAt()))
			.param("expires", Timestamp.from(offer.expiresAt()))
			.update() == 1;
	}

	/** 아직 유효한 '제안됨'(만료 전). */
	Optional<StoredOffer> findOffered(UUID memberId, Instant now) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM feeling_calibration_offer WHERE member_id = :m "
				+ "AND status = 'OFFERED' AND expires_at > :now")
			.param("m", memberId)
			.param("now", Timestamp.from(now))
			.query(CalibrationOfferRepository::map)
			.optional();
	}

	/** '제안됨' 상태(만료 여부 무관)를 잠그고 읽는다 — 민감도 변경 구독용. */
	Optional<StoredOffer> findOfferedForUpdate(UUID memberId) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM feeling_calibration_offer WHERE member_id = :m "
				+ "AND status = 'OFFERED' FOR UPDATE")
			.param("m", memberId)
			.query(CalibrationOfferRepository::map)
			.optional();
	}

	Optional<StoredOffer> findForUpdate(UUID offerId, UUID memberId) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM feeling_calibration_offer WHERE id = :id AND member_id = :m "
				+ "FOR UPDATE")
			.param("id", offerId)
			.param("m", memberId)
			.query(CalibrationOfferRepository::map)
			.optional();
	}

	Optional<StoredOffer> findById(UUID offerId) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM feeling_calibration_offer WHERE id = :id")
			.param("id", offerId)
			.query(CalibrationOfferRepository::map)
			.optional();
	}

	void updateStatus(UUID offerId, CalibrationOfferStatus status, Instant respondedAt) {
		jdbc.sql("UPDATE feeling_calibration_offer SET status = :status, responded_at = :at WHERE id = :id")
			.param("status", status.name())
			.param("at", respondedAt == null ? null : Timestamp.from(respondedAt), java.sql.Types.TIMESTAMP)
			.param("id", offerId)
			.update();
	}

	/** 유효 기간이 지난 '제안됨'을 '만료됨'으로. */
	int expireOverdue(Instant now) {
		return jdbc.sql("UPDATE feeling_calibration_offer SET status = 'EXPIRED' WHERE status = 'OFFERED' "
				+ "AND expires_at <= :now")
			.param("now", Timestamp.from(now))
			.update();
	}

	Set<UUID> offeredMembers() {
		return new HashSet<>(jdbc.sql("SELECT member_id FROM feeling_calibration_offer WHERE status = 'OFFERED'")
			.query(UUID.class)
			.list());
	}

	Map<UUID, MemberState> findStates(Collection<UUID> memberIds) {
		Map<UUID, MemberState> states = new HashMap<>();
		if (memberIds.isEmpty()) {
			return states;
		}
		jdbc.sql("SELECT member_id, last_judged_at, last_declined_at, last_sensitivity_changed_at "
				+ "FROM feeling_calibration_member WHERE member_id IN (:ids)")
			.param("ids", List.copyOf(memberIds))
			.query((rs, n) -> {
				states.put(rs.getObject("member_id", UUID.class),
						new MemberState(instant(rs.getTimestamp("last_judged_at")),
								instant(rs.getTimestamp("last_declined_at")),
								instant(rs.getTimestamp("last_sensitivity_changed_at"))));
				return null;
			})
			.list();
		return states;
	}

	void markJudged(UUID memberId, Instant at) {
		upsert(memberId, "last_judged_at", at);
	}

	void markDeclined(UUID memberId, Instant at) {
		upsert(memberId, "last_declined_at", at);
	}

	void markSensitivityChanged(UUID memberId, Instant at) {
		upsert(memberId, "last_sensitivity_changed_at", at);
	}

	/** 탈퇴: 그 회원의 보정 제안 이력과 기준 시각을 모두 지운다(FR-FEL-09). 다시 실행해도 결과가 같다. */
	void deleteMember(UUID memberId) {
		jdbc.sql("DELETE FROM feeling_calibration_offer WHERE member_id = :m").param("m", memberId).update();
		jdbc.sql("DELETE FROM feeling_calibration_member WHERE member_id = :m").param("m", memberId).update();
	}

	/** 같은 이벤트가 다시 와도 기준 시각이 뒤로 가지 않게 큰 값을 남긴다. */
	private void upsert(UUID memberId, String column, Instant at) {
		jdbc.sql("INSERT INTO feeling_calibration_member (member_id, " + column + ") VALUES (:m, :at) "
				+ "ON CONFLICT (member_id) DO UPDATE SET " + column + " = GREATEST(feeling_calibration_member." + column
				+ ", EXCLUDED." + column + ")")
			.param("m", memberId)
			.param("at", Timestamp.from(at))
			.update();
	}

	private static StoredOffer map(ResultSet rs, int rowNum) throws SQLException {
		Map<TemperatureSensitivity, Double> errors = new EnumMap<>(TemperatureSensitivity.class);
		errors.put(TemperatureSensitivity.HEAT_SENSITIVE, rs.getDouble("error_heat_sensitive"));
		errors.put(TemperatureSensitivity.NORMAL, rs.getDouble("error_normal"));
		errors.put(TemperatureSensitivity.COLD_SENSITIVE, rs.getDouble("error_cold_sensitive"));
		UUID id = rs.getObject("id", UUID.class);
		CalibrationOffer offer = new CalibrationOffer(id,
				TemperatureSensitivity.valueOf(rs.getString("current_sensitivity")),
				TemperatureSensitivity.valueOf(rs.getString("suggested_sensitivity")), rs.getInt("record_count"),
				rs.getDouble("weighted_record_count"), errors, rs.getTimestamp("judged_at").toInstant(),
				rs.getTimestamp("expires_at").toInstant(), CalibrationOfferStatus.valueOf(rs.getString("status")));
		return new StoredOffer(id, rs.getObject("member_id", UUID.class), offer, rs.getInt("mapping_version"));
	}

	private static Instant instant(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant();
	}

}
