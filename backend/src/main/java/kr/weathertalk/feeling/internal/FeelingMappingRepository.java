package kr.weathertalk.feeling.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import kr.weathertalk.feeling.HumidityTag;
import kr.weathertalk.feeling.MappingLayer;
import kr.weathertalk.feeling.TemperatureTag;
import kr.weathertalk.feeling.WindTag;
import kr.weathertalk.member.TemperatureSensitivity;
import tools.jackson.databind.json.JsonMapper;

/**
 * 체감 매핑 버전·셀 저장소 (feeling_mapping_version, feeling_mapping_cell). 설정값·검증 결과·적중률은 jsonb로 버전에 함께 둔다.
 */
@Repository
class FeelingMappingRepository {

	/** 매핑 버전 상태 (04 §3.5). */
	enum Status {

		CREATED, APPLIED, ARCHIVED, DISCARDED, REJECTED

	}

	/** 매핑 버전 요약(셀 제외). */
	record VersionRow(int version, Status status, Instant createdAt, Instant inputFrom, Instant inputTo,
			String validation, String hitRate, Instant appliedAt, Instant archivedAt) {
	}

	private final JdbcClient jdbc;
	private final JsonMapper json;

	FeelingMappingRepository(JdbcClient jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	/** 재계산을 트랜잭션 끝까지 한 줄로 세운다(정기 작업 잠금과 별개로, 운영 재실행이 겹쳐도 버전 번호가 엉키지 않게). */
	void lock() {
		jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('feeling_mapping_version', 0))")
			.query((rs, n) -> 1)
			.single();
	}

	int nextVersion() {
		return jdbc.sql("SELECT CAST(nextval('feeling_mapping_version_seq') AS integer)").query(Integer.class).single();
	}

	Optional<Instant> lastCreatedAt() {
		return jdbc.sql("SELECT max(created_at) FROM feeling_mapping_version")
			.query((rs, n) -> rs.getTimestamp(1))
			.optional()
			.map(Timestamp::toInstant);
	}

	void insertVersion(int version, Instant createdAt, Instant inputFrom, Instant inputTo, MappingSettings settings,
			Object validation, Object hitRate) {
		jdbc.sql("INSERT INTO feeling_mapping_version (version, status, created_at, input_from, input_to, settings, "
				+ "validation, hit_rate) VALUES (:v, 'CREATED', :created, :from, :to, CAST(:settings AS jsonb), "
				+ "CAST(:validation AS jsonb), CAST(:hitRate AS jsonb))")
			.param("v", version)
			.param("created", Timestamp.from(createdAt))
			.param("from", Timestamp.from(inputFrom))
			.param("to", Timestamp.from(inputTo))
			.param("settings", json.writeValueAsString(settings))
			.param("validation", json.writeValueAsString(validation))
			.param("hitRate", json.writeValueAsString(hitRate))
			.update();
	}

	void insertCells(int version, List<MappingCell> cells) {
		for (MappingCell c : cells) {
			MappingCell.Key k = c.key();
			jdbc.sql("INSERT INTO feeling_mapping_cell (version, layer, temperature_sensitivity, season, temperature_bin, "
					+ "humidity_bin, wind_bin, temperature_level, humidity_level, wind_level, sample_count, weighted_count) "
					+ "VALUES (:v, :layer, :sens, :season, :t, :h, :w, :tl, :hl, :wl, :count, :weighted)")
				.param("v", version)
				.param("layer", k.layer().name())
				.param("sens", k.sensitivity().name())
				.param("season", k.season() == null ? null : k.season().name(), java.sql.Types.VARCHAR)
				.param("t", k.temperatureBin())
				.param("h", k.humidityBin(), java.sql.Types.SMALLINT)
				.param("w", k.windBin(), java.sql.Types.SMALLINT)
				.param("tl", c.temperature().level())
				.param("hl", c.humidity() == null ? null : c.humidity().level(), java.sql.Types.SMALLINT)
				.param("wl", c.wind() == null ? null : c.wind().level(), java.sql.Types.SMALLINT)
				.param("count", c.sampleCount())
				.param("weighted", c.weightedCount())
				.update();
		}
	}

	void updateStatus(int version, Status status, Instant at) {
		String column = switch (status) {
			case APPLIED -> ", applied_at = :at";
			case ARCHIVED -> ", archived_at = :at";
			default -> "";
		};
		jdbc.sql("UPDATE feeling_mapping_version SET status = :status" + column + " WHERE version = :v")
			.param("status", status.name())
			.param("at", Timestamp.from(at))
			.param("v", version)
			.update();
	}

	Optional<Integer> appliedVersion() {
		return jdbc.sql("SELECT version FROM feeling_mapping_version WHERE status = 'APPLIED'")
			.query(Integer.class)
			.optional();
	}

	/** 보관 버전 중 최근 retained개를 넘는 것(오래된 것). */
	List<Integer> archivedBeyond(int retained) {
		return jdbc.sql("SELECT version FROM feeling_mapping_version WHERE status = 'ARCHIVED' ORDER BY version DESC "
				+ "OFFSET :n")
			.param("n", retained)
			.query(Integer.class)
			.list();
	}

	/** 폐기: 셀을 지우고 버전 요약(설정·검증·적중률)은 남긴다. */
	void discard(int version) {
		jdbc.sql("DELETE FROM feeling_mapping_cell WHERE version = :v").param("v", version).update();
		jdbc.sql("UPDATE feeling_mapping_version SET status = 'DISCARDED' WHERE version = :v")
			.param("v", version)
			.update();
	}

	Optional<VersionRow> findVersion(int version) {
		return jdbc.sql("SELECT version, status, created_at, input_from, input_to, validation::text AS validation, "
				+ "hit_rate::text AS hit_rate, applied_at, archived_at FROM feeling_mapping_version WHERE version = :v")
			.param("v", version)
			.query((rs, n) -> new VersionRow(rs.getInt("version"), Status.valueOf(rs.getString("status")),
					rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("input_from").toInstant(),
					rs.getTimestamp("input_to").toInstant(), rs.getString("validation"), rs.getString("hit_rate"),
					instant(rs.getTimestamp("applied_at")), instant(rs.getTimestamp("archived_at"))))
			.optional();
	}

	int cellCount(int version) {
		return jdbc.sql("SELECT count(*) FROM feeling_mapping_cell WHERE version = :v")
			.param("v", version)
			.query(Integer.class)
			.single();
	}

	/** 버전 하나를 셀과 함께 메모리로 올린다. 그 버전을 만들 때의 설정값(구간 정의)도 함께. */
	Optional<LoadedMapping> load(int version) {
		Optional<String> settingsJson = jdbc.sql("SELECT settings::text FROM feeling_mapping_version WHERE version = :v")
			.param("v", version)
			.query(String.class)
			.optional();
		if (settingsJson.isEmpty()) {
			return Optional.empty();
		}
		MappingSettings settings = json.readValue(settingsJson.get(), MappingSettings.class);
		Map<MappingCell.Key, MappingCell> cells = new LinkedHashMap<>();
		jdbc.sql("SELECT layer, temperature_sensitivity, season, temperature_bin, humidity_bin, wind_bin, "
				+ "temperature_level, humidity_level, wind_level, sample_count, weighted_count FROM feeling_mapping_cell "
				+ "WHERE version = :v")
			.param("v", version)
			.query((rs, n) -> {
				String season = rs.getString("season");
				Integer h = (Integer) rs.getObject("humidity_bin", Integer.class);
				Integer w = (Integer) rs.getObject("wind_bin", Integer.class);
				Integer hl = (Integer) rs.getObject("humidity_level", Integer.class);
				Integer wl = (Integer) rs.getObject("wind_level", Integer.class);
				MappingCell.Key key = new MappingCell.Key(MappingLayer.valueOf(rs.getString("layer")),
						TemperatureSensitivity.valueOf(rs.getString("temperature_sensitivity")),
						season == null ? null : Season.valueOf(season), rs.getInt("temperature_bin"), h, w);
				return new MappingCell(key, TemperatureTag.of(rs.getInt("temperature_level")),
						hl == null ? null : HumidityTag.of(hl), wl == null ? null : WindTag.of(wl),
						rs.getInt("sample_count"), rs.getDouble("weighted_count"));
			})
			.list()
			.forEach(cell -> cells.put(cell.key(), cell));
		return Optional.of(new LoadedMapping(version, settings, Map.copyOf(cells)));
	}

	private static Instant instant(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant();
	}

}
