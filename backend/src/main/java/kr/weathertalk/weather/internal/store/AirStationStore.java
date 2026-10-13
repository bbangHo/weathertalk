package kr.weathertalk.weather.internal.store;

import static kr.weathertalk.weather.internal.store.StoreSupport.ts;

import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import kr.weathertalk.neighborhood.GeoPoint;
import kr.weathertalk.weather.internal.source.SourceData;

/**
 * 에어코리아 측정소 목록 (03 §3.6). 목록 동기화 결과를 통째로 반영하고(신설 추가·폐지 표시·재등장 복원),
 * 동네 대표 좌표에서 가까운 순 측정소를 PostGIS 최근접(geography, 미터)으로 계산한다.
 */
@Repository
public class AirStationStore {

	public record SyncResult(int active, int added, int closed) {
	}

	private final JdbcClient jdbc;
	private final NamedParameterJdbcTemplate named;

	public AirStationStore(JdbcClient jdbc, NamedParameterJdbcTemplate named) {
		this.jdbc = jdbc;
		this.named = named;
	}

	/** 받은 목록을 전체로 보고 반영한다. 목록에 없는 측정소는 폐지로 표시해 대응에서 뺀다(측정 이력은 남긴다). */
	@Transactional
	public SyncResult replaceAll(List<SourceData.AirStation> stations, Instant syncedAt) {
		int before = jdbc.sql("SELECT count(*) FROM weather_air_station").query(Integer.class).single();
		named.batchUpdate("""
				INSERT INTO weather_air_station (station_name, address, network_name, measured_items, installed_year,
				    location, synced_at, closed_at)
				VALUES (:name, :address, :network, :items, :year,
				    CASE WHEN CAST(:lon AS double precision) IS NULL OR CAST(:lat AS double precision) IS NULL THEN NULL
				         ELSE ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography END,
				    :syncedAt, NULL)
				ON CONFLICT (station_name) DO UPDATE SET
				    address = EXCLUDED.address, network_name = EXCLUDED.network_name,
				    measured_items = EXCLUDED.measured_items, installed_year = EXCLUDED.installed_year,
				    location = EXCLUDED.location, synced_at = EXCLUDED.synced_at, closed_at = NULL
				""", stations.stream()
			.map(s -> new MapSqlParameterSource().addValue("name", s.name())
				.addValue("address", s.address(), java.sql.Types.VARCHAR)
				.addValue("network", s.network(), java.sql.Types.VARCHAR)
				.addValue("items", s.measuredItems(), java.sql.Types.VARCHAR)
				.addValue("year", s.installedYear(), java.sql.Types.VARCHAR)
				.addValue("lon", s.longitude(), java.sql.Types.DOUBLE)
				.addValue("lat", s.latitude(), java.sql.Types.DOUBLE)
				.addValue("syncedAt", ts(syncedAt)))
			.toArray(MapSqlParameterSource[]::new));
		int closed = jdbc.sql("""
				UPDATE weather_air_station SET closed_at = :syncedAt
				WHERE synced_at < :syncedAt AND closed_at IS NULL
				""").param("syncedAt", ts(syncedAt)).update();
		int total = jdbc.sql("SELECT count(*) FROM weather_air_station").query(Integer.class).single();
		return new SyncResult(activeCount(), total - before, closed);
	}

	public int activeCount() {
		return jdbc.sql("SELECT count(*) FROM weather_air_station WHERE closed_at IS NULL")
			.query(Integer.class)
			.single();
	}

	/** 대표 좌표에서 가까운 순 운영 중 측정소 이름(최대 limit곳). 좌표가 없는 측정소는 뺀다. */
	public List<String> nearest(GeoPoint point, int limit) {
		return jdbc.sql("""
				SELECT station_name FROM weather_air_station
				WHERE closed_at IS NULL AND location IS NOT NULL
				ORDER BY location <-> ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography, station_name
				LIMIT :limit
				""")
			.param("lon", point.longitude())
			.param("lat", point.latitude())
			.param("limit", limit)
			.query(String.class)
			.list();
	}

}
