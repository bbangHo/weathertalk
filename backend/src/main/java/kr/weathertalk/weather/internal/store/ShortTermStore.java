package kr.weathertalk.weather.internal.store;

import static kr.weathertalk.weather.internal.store.StoreSupport.amount;
import static kr.weathertalk.weather.internal.store.StoreSupport.dbl;
import static kr.weathertalk.weather.internal.store.StoreSupport.enumValue;
import static kr.weathertalk.weather.internal.store.StoreSupport.instant;
import static kr.weathertalk.weather.internal.store.StoreSupport.integer;
import static kr.weathertalk.weather.internal.store.StoreSupport.ts;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import kr.weathertalk.neighborhood.ForecastGrid;
import kr.weathertalk.weather.PrecipitationType;
import kr.weathertalk.weather.SkyCondition;
import kr.weathertalk.weather.internal.source.SourceData;

/**
 * 단기예보 저장. 한 (격자, 발표 시각)의 칸은 한 트랜잭션에서 통째로 교체한다 — 항목별 부분 갱신 없음(legacy 04-#2),
 * 같은 발표분을 다시 받아도 키 충돌 없이 멱등(legacy 04-#19). 칸 키는 날짜를 포함한 예보 시각이다(legacy 04-#23).
 */
@Repository
public class ShortTermStore {

	public record Issue(Instant issuedAt, List<SourceData.ShortTermSlot> slots) {
	}

	private final JdbcClient jdbc;
	private final NamedParameterJdbcTemplate named;

	public ShortTermStore(JdbcClient jdbc, NamedParameterJdbcTemplate named) {
		this.jdbc = jdbc;
		this.named = named;
	}

	@Transactional
	public void replace(ForecastGrid grid, Instant issuedAt, List<SourceData.ShortTermSlot> slots, Instant collectedAt) {
		jdbc.sql("DELETE FROM weather_short_term_issue WHERE grid_nx = :nx AND grid_ny = :ny AND issued_at = :issued")
			.param("nx", grid.nx())
			.param("ny", grid.ny())
			.param("issued", ts(issuedAt))
			.update();
		jdbc.sql("""
				INSERT INTO weather_short_term_issue (grid_nx, grid_ny, issued_at, collected_at)
				VALUES (:nx, :ny, :issued, :collectedAt)
				""")
			.param("nx", grid.nx())
			.param("ny", grid.ny())
			.param("issued", ts(issuedAt))
			.param("collectedAt", ts(collectedAt))
			.update();
		MapSqlParameterSource[] rows = slots.stream().map(s -> new MapSqlParameterSource()
			.addValue("nx", grid.nx())
			.addValue("ny", grid.ny())
			.addValue("issued", ts(issuedAt))
			.addValue("at", ts(s.forecastAt()))
			.addValue("temperature", s.temperature(), java.sql.Types.DOUBLE)
			.addValue("humidity", s.humidity(), java.sql.Types.DOUBLE)
			.addValue("wind", s.windSpeed(), java.sql.Types.DOUBLE)
			.addValue("pop", s.precipitationProbability(), java.sql.Types.INTEGER)
			.addValue("type", StoreSupport.name(s.precipitationType()), java.sql.Types.VARCHAR)
			.addValue("pl", StoreSupport.lower(s.precipitation()), java.sql.Types.DOUBLE)
			.addValue("pu", StoreSupport.upper(s.precipitation()), java.sql.Types.DOUBLE)
			.addValue("pe", StoreSupport.exclusive(s.precipitation()), java.sql.Types.BOOLEAN)
			.addValue("sl", StoreSupport.lower(s.snowfall()), java.sql.Types.DOUBLE)
			.addValue("su", StoreSupport.upper(s.snowfall()), java.sql.Types.DOUBLE)
			.addValue("se", StoreSupport.exclusive(s.snowfall()), java.sql.Types.BOOLEAN)
			.addValue("sky", StoreSupport.name(s.sky()), java.sql.Types.VARCHAR)
			.addValue("tmx", s.dailyMax(), java.sql.Types.DOUBLE)
			.addValue("tmn", s.dailyMin(), java.sql.Types.DOUBLE)).toArray(MapSqlParameterSource[]::new);
		named.batchUpdate("""
				INSERT INTO weather_short_term_slot (grid_nx, grid_ny, issued_at, forecast_at, temperature, humidity,
				    wind_speed, precipitation_probability, precipitation_type, precip_lower, precip_upper,
				    precip_lower_exclusive, snow_lower, snow_upper, snow_lower_exclusive, sky, daily_max, daily_min)
				VALUES (:nx, :ny, :issued, :at, :temperature, :humidity, :wind, :pop, :type, :pl, :pu, :pe,
				    :sl, :su, :se, :sky, :tmx, :tmn)
				""", rows);
	}

	public Optional<Instant> latestIssuedAt(ForecastGrid grid) {
		return jdbc
			.sql("SELECT max(issued_at) AS latest FROM weather_short_term_issue WHERE grid_nx = :nx AND grid_ny = :ny")
			.param("nx", grid.nx())
			.param("ny", grid.ny())
			.query((rs, n) -> Optional.ofNullable(instant(rs, "latest")))
			.single();
	}

	/** 저장된 가장 최근 발표분(그 시각 이전 발표 중). */
	@Transactional(readOnly = true)
	public Optional<Issue> findLatestIssuedAtOrBefore(ForecastGrid grid, Instant at) {
		Optional<Instant> issuedAt = jdbc.sql("""
				SELECT max(issued_at) AS latest FROM weather_short_term_issue
				WHERE grid_nx = :nx AND grid_ny = :ny AND issued_at <= :at
				""")
			.param("nx", grid.nx())
			.param("ny", grid.ny())
			.param("at", ts(at))
			.query((rs, n) -> Optional.ofNullable(instant(rs, "latest")))
			.single();
		return issuedAt.map(issued -> new Issue(issued, slots(grid, issued)));
	}

	/** 그 시각 이전 발표분 중 가장 최근 것에서 그 예보 시각의 칸. */
	public Optional<SourceData.ShortTermSlot> findSlot(ForecastGrid grid, Instant forecastAt, Instant issuedAtOrBefore) {
		return jdbc.sql("""
				SELECT * FROM weather_short_term_slot
				WHERE grid_nx = :nx AND grid_ny = :ny AND forecast_at = :at AND issued_at <= :issued
				ORDER BY issued_at DESC LIMIT 1
				""")
			.param("nx", grid.nx())
			.param("ny", grid.ny())
			.param("at", ts(forecastAt))
			.param("issued", ts(issuedAtOrBefore))
			.query(ShortTermStore::slot)
			.optional();
	}

	private List<SourceData.ShortTermSlot> slots(ForecastGrid grid, Instant issuedAt) {
		return jdbc.sql("""
				SELECT * FROM weather_short_term_slot WHERE grid_nx = :nx AND grid_ny = :ny AND issued_at = :issued
				ORDER BY forecast_at
				""")
			.param("nx", grid.nx())
			.param("ny", grid.ny())
			.param("issued", ts(issuedAt))
			.query(ShortTermStore::slot)
			.list();
	}

	/** 모든 칸의 예보 시각이 지난 발표분을 지운다. 격자마다 마지막 발표분은 남긴다(FR-WEA-11). */
	public int deletePast(Instant now) {
		return jdbc.sql("""
				DELETE FROM weather_short_term_issue i
				WHERE NOT EXISTS (SELECT 1 FROM weather_short_term_slot s WHERE s.grid_nx = i.grid_nx
				                  AND s.grid_ny = i.grid_ny AND s.issued_at = i.issued_at AND s.forecast_at >= :now)
				AND i.issued_at < (SELECT max(l.issued_at) FROM weather_short_term_issue l
				                   WHERE l.grid_nx = i.grid_nx AND l.grid_ny = i.grid_ny)
				""").param("now", ts(now)).update();
	}

	private static SourceData.ShortTermSlot slot(ResultSet rs, int n) throws SQLException {
		return new SourceData.ShortTermSlot(instant(rs, "forecast_at"), dbl(rs, "temperature"), dbl(rs, "humidity"),
				dbl(rs, "wind_speed"), integer(rs, "precipitation_probability"),
				enumValue(rs, "precipitation_type", PrecipitationType::valueOf), amount(rs, "precip", "mm"),
				amount(rs, "snow", "cm"), enumValue(rs, "sky", SkyCondition::valueOf), dbl(rs, "daily_max"),
				dbl(rs, "daily_min"));
	}

}
