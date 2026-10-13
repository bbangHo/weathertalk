package kr.weathertalk.weather.internal.store;

import static kr.weathertalk.weather.internal.store.StoreSupport.dbl;
import static kr.weathertalk.weather.internal.store.StoreSupport.enumValue;
import static kr.weathertalk.weather.internal.store.StoreSupport.instant;
import static kr.weathertalk.weather.internal.store.StoreSupport.ts;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import kr.weathertalk.weather.AirGrade;
import kr.weathertalk.weather.internal.source.SourceData;

/**
 * 대기질 측정 저장. 측정은 (측정소, 측정 시각)마다 교체하며, 수집 실패가 이전 값을 지우지 않는다(legacy 04-#4).
 * 수집 단위는 시도별 실시간 측정 한 번(전국)이라, 그 단위의 '저장된 최신 측정 시각'은 전 측정소의 최대값이다.
 */
@Repository
public class AirStore {

	private final JdbcClient jdbc;
	private final NamedParameterJdbcTemplate named;

	public AirStore(JdbcClient jdbc, NamedParameterJdbcTemplate named) {
		this.jdbc = jdbc;
		this.named = named;
	}

	@Transactional
	public void saveMeasurements(List<SourceData.StationAirMeasurement> rows, Instant collectedAt) {
		named.batchUpdate("""
				INSERT INTO weather_air_measurement (station_name, measured_at, pm10, pm25, pm10_grade, pm25_grade,
				    ozone_grade, collected_at)
				VALUES (:station, :at, :pm10, :pm25, :pm10Grade, :pm25Grade, :ozone, :collectedAt)
				ON CONFLICT (station_name, measured_at) DO UPDATE SET
				    pm10 = EXCLUDED.pm10, pm25 = EXCLUDED.pm25, pm10_grade = EXCLUDED.pm10_grade,
				    pm25_grade = EXCLUDED.pm25_grade, ozone_grade = EXCLUDED.ozone_grade,
				    collected_at = EXCLUDED.collected_at
				""", rows.stream().map(row -> {
			SourceData.AirMeasurement m = row.measurement();
			return new MapSqlParameterSource().addValue("station", row.stationName())
				.addValue("at", ts(m.measuredAt()))
				.addValue("pm10", m.pm10(), java.sql.Types.DOUBLE)
				.addValue("pm25", m.pm25(), java.sql.Types.DOUBLE)
				.addValue("pm10Grade", StoreSupport.name(m.pm10Grade()), java.sql.Types.VARCHAR)
				.addValue("pm25Grade", StoreSupport.name(m.pm25Grade()), java.sql.Types.VARCHAR)
				.addValue("ozone", StoreSupport.name(m.ozoneGrade()), java.sql.Types.VARCHAR)
				.addValue("collectedAt", ts(collectedAt));
		}).toArray(MapSqlParameterSource[]::new));
	}

	/** 전 측정소 중 가장 최근 측정 시각. */
	public Optional<Instant> latestMeasuredAt() {
		return jdbc.sql("SELECT max(measured_at) AS latest FROM weather_air_measurement")
			.query((rs, n) -> Optional.ofNullable(instant(rs, "latest")))
			.single();
	}

	/** 그 시각 이전(포함) 가장 가까운 측정. */
	public Optional<SourceData.AirMeasurement> findLatestAtOrBefore(String station, Instant at) {
		return jdbc.sql("""
				SELECT * FROM weather_air_measurement WHERE station_name = :station AND measured_at <= :at
				ORDER BY measured_at DESC LIMIT 1
				""")
			.param("station", station)
			.param("at", ts(at))
			.query((rs, n) -> new SourceData.AirMeasurement(instant(rs, "measured_at"), dbl(rs, "pm10"),
					dbl(rs, "pm25"), enumValue(rs, "pm10_grade", AirGrade::valueOf),
					enumValue(rs, "pm25_grade", AirGrade::valueOf), enumValue(rs, "ozone_grade", AirGrade::valueOf)))
			.optional();
	}

	/** 보존 기간이 지난 측정을 지운다. 측정소마다 마지막 값은 남긴다(FR-WEA-11). */
	public int deleteOlderThan(Instant cutoff) {
		return jdbc.sql("""
				DELETE FROM weather_air_measurement m WHERE m.measured_at < :cutoff
				AND m.measured_at < (SELECT max(l.measured_at) FROM weather_air_measurement l
				                     WHERE l.station_name = m.station_name)
				""").param("cutoff", ts(cutoff)).update();
	}

}
