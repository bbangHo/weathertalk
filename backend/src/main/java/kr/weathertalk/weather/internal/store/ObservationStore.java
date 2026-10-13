package kr.weathertalk.weather.internal.store;

import static kr.weathertalk.weather.internal.store.StoreSupport.amount;
import static kr.weathertalk.weather.internal.store.StoreSupport.dbl;
import static kr.weathertalk.weather.internal.store.StoreSupport.enumValue;
import static kr.weathertalk.weather.internal.store.StoreSupport.instant;
import static kr.weathertalk.weather.internal.store.StoreSupport.ts;

import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import kr.weathertalk.neighborhood.ForecastGrid;
import kr.weathertalk.weather.PrecipitationType;
import kr.weathertalk.weather.internal.source.SourceData;

/** 초단기실황 저장. (격자, 관측 시각)마다 한 행이며 다시 받으면 통째로 교체한다. */
@Repository
public class ObservationStore {

	private final JdbcClient jdbc;

	public ObservationStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void save(ForecastGrid grid, SourceData.Observation o, Instant collectedAt) {
		jdbc.sql("""
				INSERT INTO weather_observation (grid_nx, grid_ny, observed_at, temperature, humidity, wind_speed,
				    precip_lower, precip_upper, precip_lower_exclusive, precipitation_type, collected_at)
				VALUES (:nx, :ny, :observedAt, :temperature, :humidity, :wind, :pl, :pu, :pe, :type, :collectedAt)
				ON CONFLICT (grid_nx, grid_ny, observed_at) DO UPDATE SET
				    temperature = EXCLUDED.temperature, humidity = EXCLUDED.humidity, wind_speed = EXCLUDED.wind_speed,
				    precip_lower = EXCLUDED.precip_lower, precip_upper = EXCLUDED.precip_upper,
				    precip_lower_exclusive = EXCLUDED.precip_lower_exclusive,
				    precipitation_type = EXCLUDED.precipitation_type, collected_at = EXCLUDED.collected_at
				""")
			.param("nx", grid.nx())
			.param("ny", grid.ny())
			.param("observedAt", ts(o.observedAt()))
			.param("temperature", o.temperature(), java.sql.Types.DOUBLE)
			.param("humidity", o.humidity(), java.sql.Types.DOUBLE)
			.param("wind", o.windSpeed(), java.sql.Types.DOUBLE)
			.param("pl", StoreSupport.lower(o.precipitation()), java.sql.Types.DOUBLE)
			.param("pu", StoreSupport.upper(o.precipitation()), java.sql.Types.DOUBLE)
			.param("pe", StoreSupport.exclusive(o.precipitation()), java.sql.Types.BOOLEAN)
			.param("type", StoreSupport.name(o.precipitationType()), java.sql.Types.VARCHAR)
			.param("collectedAt", ts(collectedAt))
			.update();
	}

	public Optional<Instant> latestObservedAt(ForecastGrid grid) {
		return jdbc.sql("SELECT max(observed_at) AS latest FROM weather_observation WHERE grid_nx = :nx AND grid_ny = :ny")
			.param("nx", grid.nx())
			.param("ny", grid.ny())
			.query((rs, n) -> Optional.ofNullable(instant(rs, "latest")))
			.single();
	}

	/** 그 시각 이전(포함) 가장 가까운 관측. */
	public Optional<SourceData.Observation> findLatestAtOrBefore(ForecastGrid grid, Instant at) {
		return jdbc.sql("""
				SELECT * FROM weather_observation WHERE grid_nx = :nx AND grid_ny = :ny AND observed_at <= :at
				ORDER BY observed_at DESC LIMIT 1
				""")
			.param("nx", grid.nx())
			.param("ny", grid.ny())
			.param("at", ts(at))
			.query((rs, n) -> new SourceData.Observation(instant(rs, "observed_at"), dbl(rs, "temperature"),
					dbl(rs, "humidity"), dbl(rs, "wind_speed"), amount(rs, "precip", "mm"),
					enumValue(rs, "precipitation_type", PrecipitationType::valueOf)))
			.optional();
	}

	/** 보존 기간이 지난 관측을 지운다. 격자마다 마지막 정상값은 남긴다(FR-WEA-11). */
	public int deleteOlderThan(Instant cutoff) {
		return jdbc.sql("""
				DELETE FROM weather_observation o WHERE o.observed_at < :cutoff
				AND o.observed_at < (SELECT max(l.observed_at) FROM weather_observation l
				                     WHERE l.grid_nx = o.grid_nx AND l.grid_ny = o.grid_ny)
				""").param("cutoff", ts(cutoff)).update();
	}

}
