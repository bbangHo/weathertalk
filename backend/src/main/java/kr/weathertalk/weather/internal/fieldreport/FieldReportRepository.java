package kr.weathertalk.weather.internal.fieldreport;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import kr.weathertalk.weather.FieldReportAxis;
import kr.weathertalk.weather.FieldReportKind;
import kr.weathertalk.weather.PrecipitationType;

/** 현장 제보·현장 제보 강수 발행 기록 저장 (03 §3.7·§3.9). */
@Repository
public class FieldReportRepository {

	/** 저장된 현장 제보. 탈퇴한 회원의 제보는 memberId가 null이다. */
	public record StoredFieldReport(UUID id, String neighborhoodCode, UUID memberId, FieldReportAxis axis,
			FieldReportKind kind, Instant reportedAt, Instant replacedAt, Instant canceledAt) {

		/** 집계에 들어갈 수 있는 제보: 취소·대체되지 않았다. */
		boolean active() {
			return replacedAt == null && canceledAt == null;
		}

		/** 제보자 식별(서로 다른 회원 수). 탈퇴로 회원 ID가 비워진 제보는 한 건을 한 명으로 센다. */
		Object reporterKey() {
			return memberId != null ? memberId : id;
		}

	}

	private static final String COLUMNS = "id, neighborhood_code, member_id, axis, kind, reported_at, replaced_at, canceled_at";

	private final JdbcClient jdbc;

	FieldReportRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** 트랜잭션 끝까지 같은 키의 쓰기를 한 줄로 세운다. */
	void lock(String key) {
		jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))").param("key", key).query((rs, n) -> 1).single();
	}

	void insert(StoredFieldReport report) {
		jdbc.sql("INSERT INTO weather_field_report (" + COLUMNS + ") VALUES (:id, :nbr, :member, :axis, :kind, :at, NULL, NULL)")
			.param("id", report.id())
			.param("nbr", report.neighborhoodCode())
			.param("member", report.memberId())
			.param("axis", report.axis().name())
			.param("kind", report.kind().name())
			.param("at", Timestamp.from(report.reportedAt()))
			.update();
	}

	/** 그 회원·동네·축에서 since 이후(포함) 가장 최근의 취소·대체되지 않은 제보 — 대체 대상(BR-WEA-16). */
	Optional<StoredFieldReport> findActiveSince(UUID memberId, String neighborhoodCode, FieldReportAxis axis,
			Instant since) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM weather_field_report WHERE member_id = :member "
				+ "AND neighborhood_code = :nbr AND axis = :axis AND reported_at >= :since "
				+ "AND replaced_at IS NULL AND canceled_at IS NULL ORDER BY reported_at DESC LIMIT 1")
			.param("member", memberId)
			.param("nbr", neighborhoodCode)
			.param("axis", axis.name())
			.param("since", Timestamp.from(since))
			.query(FieldReportRepository::map)
			.optional();
	}

	void markReplaced(UUID id, Instant at) {
		jdbc.sql("UPDATE weather_field_report SET replaced_at = :at WHERE id = :id")
			.param("id", id)
			.param("at", Timestamp.from(at))
			.update();
	}

	/** [from, to) 사이에 한 제보 수. 취소·대체된 제보도 센다(한도 우회 방지, FR-WEA-14 규칙 6). */
	int countByMemberBetween(UUID memberId, Instant from, Instant to) {
		return jdbc.sql("SELECT count(*) FROM weather_field_report WHERE member_id = :member "
				+ "AND reported_at >= :from AND reported_at < :to")
			.param("member", memberId)
			.param("from", Timestamp.from(from))
			.param("to", Timestamp.from(to))
			.query(Integer.class)
			.single();
	}

	Optional<StoredFieldReport> findForUpdate(UUID id) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM weather_field_report WHERE id = :id FOR UPDATE")
			.param("id", id)
			.query(FieldReportRepository::map)
			.optional();
	}

	void markCanceled(UUID id, Instant at) {
		jdbc.sql("UPDATE weather_field_report SET canceled_at = :at WHERE id = :id")
			.param("id", id)
			.param("at", Timestamp.from(at))
			.update();
	}

	/** 동네의 (from, to] 제보 중 취소·대체되지 않은 것. */
	List<StoredFieldReport> findActive(String neighborhoodCode, Instant from, Instant to) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM weather_field_report WHERE neighborhood_code = :nbr "
				+ "AND reported_at >= :from AND reported_at <= :to AND replaced_at IS NULL AND canceled_at IS NULL "
				+ "ORDER BY reported_at DESC")
			.param("nbr", neighborhoodCode)
			.param("from", Timestamp.from(from))
			.param("to", Timestamp.from(to))
			.query(FieldReportRepository::map)
			.list();
	}

	Optional<Instant> lastPrecipPublishedAt(String neighborhoodCode, PrecipitationType type) {
		return jdbc.sql("SELECT last_published_at FROM weather_field_report_precip_publication "
				+ "WHERE neighborhood_code = :nbr AND precipitation_type = :type")
			.param("nbr", neighborhoodCode)
			.param("type", type.name())
			.query((rs, n) -> rs.getTimestamp(1).toInstant())
			.optional();
	}

	void savePrecipPublication(String neighborhoodCode, PrecipitationType type, Instant at, int reporterCount) {
		jdbc.sql("""
				INSERT INTO weather_field_report_precip_publication (neighborhood_code, precipitation_type,
				    last_published_at, reporter_count)
				VALUES (:nbr, :type, :at, :count)
				ON CONFLICT (neighborhood_code, precipitation_type) DO UPDATE SET
				    last_published_at = EXCLUDED.last_published_at, reporter_count = EXCLUDED.reporter_count
				""")
			.param("nbr", neighborhoodCode)
			.param("type", type.name())
			.param("at", Timestamp.from(at))
			.param("count", reporterCount)
			.update();
	}

	/** 회원탈퇴함: 그 회원의 제보에서 회원 ID만 비운다(멱등). */
	int anonymize(UUID memberId) {
		return jdbc.sql("UPDATE weather_field_report SET member_id = NULL WHERE member_id = :member")
			.param("member", memberId)
			.update();
	}

	int deleteReportedBefore(Instant cutoff) {
		return jdbc.sql("DELETE FROM weather_field_report WHERE reported_at < :cutoff")
			.param("cutoff", Timestamp.from(cutoff))
			.update();
	}

	int deletePrecipPublicationsBefore(Instant cutoff) {
		return jdbc.sql("DELETE FROM weather_field_report_precip_publication WHERE last_published_at < :cutoff")
			.param("cutoff", Timestamp.from(cutoff))
			.update();
	}

	private static StoredFieldReport map(ResultSet rs, int n) throws SQLException {
		return new StoredFieldReport(rs.getObject("id", UUID.class), rs.getString("neighborhood_code"),
				rs.getObject("member_id", UUID.class), FieldReportAxis.valueOf(rs.getString("axis")),
				FieldReportKind.valueOf(rs.getString("kind")), instant(rs, "reported_at"), instant(rs, "replaced_at"),
				instant(rs, "canceled_at"));
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp ts = rs.getTimestamp(column);
		return ts == null ? null : ts.toInstant();
	}

}
