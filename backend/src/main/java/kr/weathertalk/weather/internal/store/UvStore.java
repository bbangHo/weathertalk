package kr.weathertalk.weather.internal.store;

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

import kr.weathertalk.weather.internal.source.SourceData;

/** 자외선 지수 저장. (구역, 발표 시각) 단위로 통째 교체한다. */
@Repository
public class UvStore {

	public record UvIssue(Instant issuedAt, List<SourceData.UvValue> values) {
	}

	private final JdbcClient jdbc;
	private final NamedParameterJdbcTemplate named;

	public UvStore(JdbcClient jdbc, NamedParameterJdbcTemplate named) {
		this.jdbc = jdbc;
		this.named = named;
	}

	@Transactional
	public void replace(String areaNo, Instant issuedAt, List<SourceData.UvValue> values, Instant collectedAt) {
		jdbc.sql("DELETE FROM weather_uv_index WHERE area_no = :area AND issued_at = :issued")
			.param("area", areaNo)
			.param("issued", ts(issuedAt))
			.update();
		named.batchUpdate("""
				INSERT INTO weather_uv_index (area_no, issued_at, forecast_at, uv_value, collected_at)
				VALUES (:area, :issued, :at, :value, :collectedAt)
				""", values.stream().map(v -> new MapSqlParameterSource()
			.addValue("area", areaNo)
			.addValue("issued", ts(issuedAt))
			.addValue("at", ts(v.forecastAt()))
			.addValue("value", v.value(), java.sql.Types.INTEGER)
			.addValue("collectedAt", ts(collectedAt))).toArray(MapSqlParameterSource[]::new));
	}

	public Optional<Instant> latestIssuedAt(String areaNo) {
		return jdbc.sql("SELECT max(issued_at) AS latest FROM weather_uv_index WHERE area_no = :area")
			.param("area", areaNo)
			.query((rs, n) -> Optional.ofNullable(instant(rs, "latest")))
			.single();
	}

	/** 그 시각 이전 발표분 중 가장 최근 것. */
	@Transactional(readOnly = true)
	public Optional<UvIssue> findLatestIssuedAtOrBefore(String areaNo, Instant at) {
		Optional<Instant> issued = jdbc.sql("""
				SELECT max(issued_at) AS latest FROM weather_uv_index WHERE area_no = :area AND issued_at <= :at
				""")
			.param("area", areaNo)
			.param("at", ts(at))
			.query((rs, n) -> Optional.ofNullable(instant(rs, "latest")))
			.single();
		return issued.map(i -> new UvIssue(i, jdbc.sql("""
				SELECT forecast_at, uv_value FROM weather_uv_index WHERE area_no = :area AND issued_at = :issued
				ORDER BY forecast_at
				""").param("area", areaNo).param("issued", ts(i))
			.query((rs, n) -> new SourceData.UvValue(instant(rs, "forecast_at"), integer(rs, "uv_value")))
			.list()));
	}

	/** 대체된 발표분을 지운다(FR-WEA-11). */
	public int deleteSuperseded() {
		return jdbc.sql("""
				DELETE FROM weather_uv_index u WHERE u.issued_at < (SELECT max(l.issued_at) FROM weather_uv_index l
				                                                    WHERE l.area_no = u.area_no)
				""").update();
	}

}
