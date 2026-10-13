package kr.weathertalk.feeling.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import kr.weathertalk.feeling.DerivationSource;
import kr.weathertalk.feeling.FeedbackAnswer;
import kr.weathertalk.feeling.FeelingRecordSource;
import kr.weathertalk.feeling.HumidityTag;
import kr.weathertalk.feeling.TemperatureTag;
import kr.weathertalk.feeling.WindTag;
import kr.weathertalk.member.TemperatureSensitivity;
import kr.weathertalk.weather.ValueSource;

/**
 * 체감 기록 저장소 (feeling_record). 태그는 단계 번호로 저장한다 — 단계 번호가 곧 순서다(04 §3.1).
 */
@Repository
class FeelingRecordRepository {

	private static final String COLUMNS = """
			id, member_id, neighborhood_code, created_at, recorded_at, temperature_level, humidity_level, wind_level,
			temperature_sensitivity, source, feedback_answer, feedback_shown_level, feedback_shown_source,
			feedback_shown_mapping_version, snapshot_status, snapshot_observed_at, snapshot_temperature, snapshot_humidity,
			snapshot_wind_speed, snapshot_pm10, snapshot_pm25, snapshot_precipitation_type, snapshot_temperature_source,
			snapshot_humidity_source, snapshot_wind_speed_source, snapshot_air_source
			""";

	private final JdbcClient jdbc;

	FeelingRecordRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** 같은 회원·같은 동네의 기록 쓰기를 트랜잭션 끝까지 한 줄로 세운다(불변식 3, 동시 요청). */
	void lockMemberNeighborhood(UUID memberId, String neighborhoodCode) {
		jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
			.param("key", "feeling_record:" + memberId + ":" + neighborhoodCode)
			.query((rs, n) -> 1)
			.single();
	}

	/** 생성 시각이 since 이후(초과)인 그 회원·동네의 기록 — 덮어쓰기 대상(BR-FEL-04). */
	Optional<StoredFeelingRecord> findCreatedAfter(UUID memberId, String neighborhoodCode, Instant since) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM feeling_record WHERE member_id = :m AND neighborhood_code = :n "
				+ "AND created_at > :since ORDER BY created_at DESC LIMIT 1")
			.param("m", memberId)
			.param("n", neighborhoodCode)
			.param("since", Timestamp.from(since))
			.query(FeelingRecordRepository::map)
			.optional();
	}

	void insert(StoredFeelingRecord r) {
		jdbc.sql("INSERT INTO feeling_record (" + COLUMNS + ") VALUES (:id, :member, :nbr, :created, :recorded, :temp, "
				+ ":hum, :wind, :sens, :source, :answer, :shownLevel, :shownSource, :shownVersion, :snapStatus, "
				+ ":snapObserved, :snapTemp, :snapHum, :snapWind, :snapPm10, :snapPm25, :snapPrecip, :snapTempSrc, "
				+ ":snapHumSrc, :snapWindSrc, :snapAirSrc)")
			.paramSource(params(r))
			.update();
	}

	/** 덮어쓰기: ID·회원·동네·생성 시각은 바꾸지 않는다. */
	void update(StoredFeelingRecord r) {
		jdbc.sql("UPDATE feeling_record SET recorded_at = :recorded, temperature_level = :temp, humidity_level = :hum, "
				+ "wind_level = :wind, temperature_sensitivity = :sens, source = :source, feedback_answer = :answer, "
				+ "feedback_shown_level = :shownLevel, feedback_shown_source = :shownSource, "
				+ "feedback_shown_mapping_version = :shownVersion, snapshot_status = :snapStatus, "
				+ "snapshot_observed_at = :snapObserved, snapshot_temperature = :snapTemp, snapshot_humidity = :snapHum, "
				+ "snapshot_wind_speed = :snapWind, snapshot_pm10 = :snapPm10, snapshot_pm25 = :snapPm25, "
				+ "snapshot_precipitation_type = :snapPrecip, snapshot_temperature_source = :snapTempSrc, "
				+ "snapshot_humidity_source = :snapHumSrc, snapshot_wind_speed_source = :snapWindSrc, "
				+ "snapshot_air_source = :snapAirSrc WHERE id = :id")
			.paramSource(params(r))
			.update();
	}

	Optional<StoredFeelingRecord> findById(UUID id) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM feeling_record WHERE id = :id")
			.param("id", id)
			.query(FeelingRecordRepository::map)
			.optional();
	}

	List<StoredFeelingRecord> findByIds(Collection<UUID> ids) {
		if (ids.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("SELECT " + COLUMNS + " FROM feeling_record WHERE id IN (:ids)")
			.param("ids", List.copyOf(ids))
			.query(FeelingRecordRepository::map)
			.list();
	}

	/** 동네 체감 집계 대상: 기록 시각이 (from, to]. 탈퇴(익명) 기록도 포함한다(BR-FEL-08). */
	List<StoredFeelingRecord> findInWindow(String neighborhoodCode, Instant fromExclusive, Instant toInclusive) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM feeling_record WHERE neighborhood_code = :n "
				+ "AND recorded_at > :from AND recorded_at <= :to")
			.param("n", neighborhoodCode)
			.param("from", Timestamp.from(fromExclusive))
			.param("to", Timestamp.from(toInclusive))
			.query(FeelingRecordRepository::map)
			.list();
	}

	/** 회원 본인의 기록 중 기록 시각이 since 이후인 것, 기록 시각 내림차순(FR-FEL-11). */
	List<StoredFeelingRecord> findByMemberRecordedAfter(UUID memberId, Instant since) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM feeling_record WHERE member_id = :m AND recorded_at > :since "
				+ "ORDER BY recorded_at DESC, id")
			.param("m", memberId)
			.param("since", Timestamp.from(since))
			.query(FeelingRecordRepository::map)
			.list();
	}

	/** 탈퇴 익명화: 회원 ID만 비운다(FR-FEL-09). 다시 실행해도 결과가 같다. */
	int anonymize(UUID memberId) {
		return jdbc.sql("UPDATE feeling_record SET member_id = NULL WHERE member_id = :m").param("m", memberId).update();
	}

	private static org.springframework.jdbc.core.namedparam.MapSqlParameterSource params(StoredFeelingRecord r) {
		var p = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
		StoredFeelingRecord.Feedback f = r.feedback();
		ObservationSnapshot s = r.snapshot();
		p.addValue("id", r.id());
		p.addValue("member", r.memberId());
		p.addValue("nbr", r.neighborhoodCode());
		p.addValue("created", Timestamp.from(r.createdAt()));
		p.addValue("recorded", Timestamp.from(r.recordedAt()));
		p.addValue("temp", r.temperature().level());
		p.addValue("hum", r.humidity() == null ? null : r.humidity().level(), java.sql.Types.SMALLINT);
		p.addValue("wind", r.wind() == null ? null : r.wind().level(), java.sql.Types.SMALLINT);
		p.addValue("sens", r.sensitivity().name());
		p.addValue("source", r.source().name());
		p.addValue("answer", f == null ? null : f.answer().name(), java.sql.Types.VARCHAR);
		p.addValue("shownLevel", f == null ? null : f.shownTemperature().level(), java.sql.Types.SMALLINT);
		p.addValue("shownSource", f == null || f.shownSource() == null ? null : f.shownSource().name(),
				java.sql.Types.VARCHAR);
		p.addValue("shownVersion", f == null ? null : f.shownMappingVersion(), java.sql.Types.INTEGER);
		p.addValue("snapStatus", s.status());
		p.addValue("snapObserved", s.observedAt() == null ? null : Timestamp.from(s.observedAt()),
				java.sql.Types.TIMESTAMP);
		p.addValue("snapTemp", s.temperature(), java.sql.Types.DOUBLE);
		p.addValue("snapHum", s.humidity(), java.sql.Types.DOUBLE);
		p.addValue("snapWind", s.windSpeed(), java.sql.Types.DOUBLE);
		p.addValue("snapPm10", s.pm10(), java.sql.Types.DOUBLE);
		p.addValue("snapPm25", s.pm25(), java.sql.Types.DOUBLE);
		p.addValue("snapPrecip", s.precipitationType(), java.sql.Types.VARCHAR);
		p.addValue("snapTempSrc", name(s.temperatureSource()), java.sql.Types.VARCHAR);
		p.addValue("snapHumSrc", name(s.humiditySource()), java.sql.Types.VARCHAR);
		p.addValue("snapWindSrc", name(s.windSpeedSource()), java.sql.Types.VARCHAR);
		p.addValue("snapAirSrc", name(s.airSource()), java.sql.Types.VARCHAR);
		return p;
	}

	private static String name(Enum<?> value) {
		return value == null ? null : value.name();
	}

	private static StoredFeelingRecord map(ResultSet rs, int rowNum) throws SQLException {
		String answer = rs.getString("feedback_answer");
		StoredFeelingRecord.Feedback feedback = answer == null ? null
				: new StoredFeelingRecord.Feedback(FeedbackAnswer.valueOf(answer),
						TemperatureTag.of(rs.getInt("feedback_shown_level")),
						enumOrNull(DerivationSource.class, rs.getString("feedback_shown_source")),
						(Integer) rs.getObject("feedback_shown_mapping_version"));
		ObservationSnapshot snapshot = new ObservationSnapshot(
				ObservationSnapshot.SECURED.equals(rs.getString("snapshot_status")),
				instant(rs.getTimestamp("snapshot_observed_at")), (Double) rs.getObject("snapshot_temperature"),
				(Double) rs.getObject("snapshot_humidity"), (Double) rs.getObject("snapshot_wind_speed"),
				(Double) rs.getObject("snapshot_pm10"), (Double) rs.getObject("snapshot_pm25"),
				rs.getString("snapshot_precipitation_type"),
				enumOrNull(ValueSource.class, rs.getString("snapshot_temperature_source")),
				enumOrNull(ValueSource.class, rs.getString("snapshot_humidity_source")),
				enumOrNull(ValueSource.class, rs.getString("snapshot_wind_speed_source")),
				enumOrNull(ValueSource.class, rs.getString("snapshot_air_source")));
		Integer humidity = (Integer) intOrNull(rs, "humidity_level");
		Integer wind = (Integer) intOrNull(rs, "wind_level");
		return new StoredFeelingRecord(rs.getObject("id", UUID.class), rs.getObject("member_id", UUID.class),
				rs.getString("neighborhood_code"), rs.getTimestamp("created_at").toInstant(),
				rs.getTimestamp("recorded_at").toInstant(), TemperatureTag.of(rs.getInt("temperature_level")),
				humidity == null ? null : HumidityTag.of(humidity), wind == null ? null : WindTag.of(wind),
				TemperatureSensitivity.valueOf(rs.getString("temperature_sensitivity")),
				FeelingRecordSource.valueOf(rs.getString("source")), feedback, snapshot);
	}

	private static Object intOrNull(ResultSet rs, String column) throws SQLException {
		int value = rs.getInt(column);
		return rs.wasNull() ? null : value;
	}

	private static Instant instant(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant();
	}

	private static <E extends Enum<E>> E enumOrNull(Class<E> type, String name) {
		return name == null ? null : Enum.valueOf(type, name);
	}

}
