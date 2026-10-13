package kr.weathertalk.weather.internal.store;

import static kr.weathertalk.weather.internal.store.StoreSupport.dbl;
import static kr.weathertalk.weather.internal.store.StoreSupport.enumValue;
import static kr.weathertalk.weather.internal.store.StoreSupport.instant;
import static kr.weathertalk.weather.internal.store.StoreSupport.integer;
import static kr.weathertalk.weather.internal.store.StoreSupport.ts;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import kr.weathertalk.weather.PrecipitationType;
import kr.weathertalk.weather.SkyCondition;
import kr.weathertalk.weather.internal.source.SourceData;

/** 중기 육상예보·기온 저장. (구역, 발표 시각) 단위로 통째 교체한다. */
@Repository
public class MidTermStore {

	public record LandIssue(Instant issuedAt, List<SourceData.MidLandDay> days) {
	}

	public record TemperatureIssue(Instant issuedAt, List<SourceData.MidTemperatureDay> days) {
	}

	private final JdbcClient jdbc;
	private final NamedParameterJdbcTemplate named;

	public MidTermStore(JdbcClient jdbc, NamedParameterJdbcTemplate named) {
		this.jdbc = jdbc;
		this.named = named;
	}

	@Transactional
	public void replaceLand(String regionId, Instant issuedAt, List<SourceData.MidLandDay> days, Instant collectedAt) {
		jdbc.sql("DELETE FROM weather_mid_land WHERE region_id = :region AND issued_at = :issued")
			.param("region", regionId)
			.param("issued", ts(issuedAt))
			.update();
		named.batchUpdate("""
				INSERT INTO weather_mid_land (region_id, issued_at, forecast_date, am_sky, pm_sky,
				    am_precipitation_probability, pm_precipitation_probability, am_precipitation_type,
				    pm_precipitation_type, collected_at)
				VALUES (:region, :issued, :date, :amSky, :pmSky, :amPop, :pmPop, :amType, :pmType, :collectedAt)
				""", days.stream().map(d -> new MapSqlParameterSource()
			.addValue("region", regionId)
			.addValue("issued", ts(issuedAt))
			.addValue("date", d.date())
			.addValue("amSky", StoreSupport.name(d.amSky()), java.sql.Types.VARCHAR)
			.addValue("pmSky", StoreSupport.name(d.pmSky()), java.sql.Types.VARCHAR)
			.addValue("amPop", d.amPrecipitationProbability(), java.sql.Types.INTEGER)
			.addValue("pmPop", d.pmPrecipitationProbability(), java.sql.Types.INTEGER)
			.addValue("amType", StoreSupport.name(d.amPrecipitationType()), java.sql.Types.VARCHAR)
			.addValue("pmType", StoreSupport.name(d.pmPrecipitationType()), java.sql.Types.VARCHAR)
			.addValue("collectedAt", ts(collectedAt))).toArray(MapSqlParameterSource[]::new));
	}

	@Transactional
	public void replaceTemperature(String regionId, Instant issuedAt, List<SourceData.MidTemperatureDay> days,
			Instant collectedAt) {
		jdbc.sql("DELETE FROM weather_mid_temperature WHERE region_id = :region AND issued_at = :issued")
			.param("region", regionId)
			.param("issued", ts(issuedAt))
			.update();
		named.batchUpdate("""
				INSERT INTO weather_mid_temperature (region_id, issued_at, forecast_date, min_temperature,
				    max_temperature, collected_at)
				VALUES (:region, :issued, :date, :min, :max, :collectedAt)
				""", days.stream().map(d -> new MapSqlParameterSource()
			.addValue("region", regionId)
			.addValue("issued", ts(issuedAt))
			.addValue("date", d.date())
			.addValue("min", d.min(), java.sql.Types.DOUBLE)
			.addValue("max", d.max(), java.sql.Types.DOUBLE)
			.addValue("collectedAt", ts(collectedAt))).toArray(MapSqlParameterSource[]::new));
	}

	public Optional<Instant> latestLandIssuedAt(String regionId) {
		return latest("weather_mid_land", regionId);
	}

	public Optional<Instant> latestTemperatureIssuedAt(String regionId) {
		return latest("weather_mid_temperature", regionId);
	}

	@Transactional(readOnly = true)
	public Optional<LandIssue> findLatestLand(String regionId) {
		return latestLandIssuedAt(regionId).map(issued -> new LandIssue(issued, jdbc.sql("""
				SELECT * FROM weather_mid_land WHERE region_id = :region AND issued_at = :issued ORDER BY forecast_date
				""").param("region", regionId).param("issued", ts(issued)).query((rs, n) -> new SourceData.MidLandDay(
				rs.getDate("forecast_date").toLocalDate(), enumValue(rs, "am_sky", SkyCondition::valueOf),
				enumValue(rs, "pm_sky", SkyCondition::valueOf), integer(rs, "am_precipitation_probability"),
				integer(rs, "pm_precipitation_probability"),
				enumValue(rs, "am_precipitation_type", PrecipitationType::valueOf),
				enumValue(rs, "pm_precipitation_type", PrecipitationType::valueOf))).list()));
	}

	@Transactional(readOnly = true)
	public Optional<TemperatureIssue> findLatestTemperature(String regionId) {
		return latestTemperatureIssuedAt(regionId).map(issued -> new TemperatureIssue(issued, jdbc.sql("""
				SELECT * FROM weather_mid_temperature WHERE region_id = :region AND issued_at = :issued
				ORDER BY forecast_date
				""").param("region", regionId).param("issued", ts(issued)).query((rs, n) -> new SourceData.MidTemperatureDay(
				rs.getDate("forecast_date").toLocalDate(), dbl(rs, "min_temperature"), dbl(rs, "max_temperature")))
			.list()));
	}

	/** 대체된(같은 구역에 더 새 발표분이 있는) 발표분을 지운다(FR-WEA-11). */
	public int deleteSuperseded() {
		int land = jdbc.sql("""
				DELETE FROM weather_mid_land m WHERE m.issued_at < (SELECT max(l.issued_at) FROM weather_mid_land l
				                                                    WHERE l.region_id = m.region_id)
				""").update();
		int temperature = jdbc.sql("""
				DELETE FROM weather_mid_temperature m WHERE m.issued_at < (SELECT max(l.issued_at)
				    FROM weather_mid_temperature l WHERE l.region_id = m.region_id)
				""").update();
		return land + temperature;
	}

	private Optional<Instant> latest(String table, String regionId) {
		return jdbc.sql("SELECT max(issued_at) AS latest FROM " + table + " WHERE region_id = :region")
			.param("region", regionId)
			.query((rs, n) -> Optional.ofNullable(instant(rs, "latest")))
			.single();
	}

}
