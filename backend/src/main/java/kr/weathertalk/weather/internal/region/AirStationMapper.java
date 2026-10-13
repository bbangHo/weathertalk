package kr.weathertalk.weather.internal.region;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import kr.weathertalk.neighborhood.GeoPoint;
import kr.weathertalk.neighborhood.Neighborhood;
import kr.weathertalk.weather.internal.collect.CollectStateRepository;
import kr.weathertalk.weather.internal.collect.CollectStateRepository.Result;
import kr.weathertalk.weather.internal.collect.CollectionKind;
import kr.weathertalk.weather.internal.config.WeatherProperties;
import kr.weathertalk.weather.internal.source.AirKoreaSource;
import kr.weathertalk.weather.internal.source.SourceException;
import kr.weathertalk.weather.internal.store.AirStore;

/**
 * 동네 → 가까운 순 측정소 대응 (03 §3.6). 동네 대표 좌표(사람의 위치 아님)를 에어코리아가 기대하는 TM 좌표로 공식 변환해
 * 근접측정소를 찾는다(legacy 04-#13). 대응은 weather.air.station-mapping-ttl마다 다시 정하고, 결측 대비로 station-fallback-count곳을 둔다.
 */
@Component
public class AirStationMapper {

	private static final Logger log = LoggerFactory.getLogger(AirStationMapper.class);

	/**
	 * 에어코리아 근접측정소 API의 TM 좌표계: Korea 2000 / Central Belt(GRS80, 중부원점 127°E·38°N, 가산 200000·500000).
	 * 레거시의 EPSG:4166→5179(UTM-K) 변환은 측정소 API 좌표계와 달라 엉뚱한 측정소를 골랐다.
	 */
	static final int AIRKOREA_TM_SRID = 5181;

	public record TmPoint(double x, double y) {
	}

	private final JdbcClient jdbc;
	private final AirStore store;
	private final AirKoreaSource source;
	private final CollectStateRepository states;
	private final WeatherProperties properties;
	private final Clock clock;

	public AirStationMapper(JdbcClient jdbc, AirStore store, AirKoreaSource source, CollectStateRepository states,
			WeatherProperties properties, Clock clock) {
		this.jdbc = jdbc;
		this.store = store;
		this.source = source;
		this.states = states;
		this.properties = properties;
		this.clock = clock;
	}

	/** 가까운 순 측정소(최대 station-fallback-count곳). 정하지 못하면 마지막 대응 또는 빈 목록. */
	public List<String> stations(Neighborhood neighborhood) {
		Instant now = clock.instant();
		Optional<AirStore.StationMapping> mapping = store.findMapping(neighborhood.id());
		if (mapping.isPresent() && mapping.get().mappedAt().plus(properties.air().stationMappingTtl()).isAfter(now)) {
			return mapping.get().stations();
		}
		List<String> fallback = mapping.map(AirStore.StationMapping::stations).orElse(List.of());
		String unit = neighborhood.id().value();
		boolean backingOff = states.find(CollectionKind.AIR_STATIONS, unit)
			.filter(s -> s.lastResult() == Result.FAILURE || s.lastResult() == Result.KEY_MISSING)
			.filter(s -> s.lastAttemptAt().plus(properties.collect().failureBackoff()).isAfter(now))
			.isPresent();
		if (backingOff) {
			return fallback;
		}
		try {
			TmPoint tm = toAirKoreaTm(neighborhood.representativePoint());
			List<String> stations = source.findNearbyStations(tm.x(), tm.y())
				.stream()
				.limit(properties.air().stationFallbackCount())
				.toList();
			store.saveMapping(neighborhood.id(), stations, now);
			states.record(CollectionKind.AIR_STATIONS, unit, now, now, Result.SUCCESS, now);
			return stations;
		}
		catch (SourceException.KeyMissing e) {
			log.warn("측정소 대응 생략: {}", e.getMessage());
			states.record(CollectionKind.AIR_STATIONS, unit, now, null, Result.KEY_MISSING, now);
		}
		catch (SourceException e) {
			log.warn("측정소 대응 실패({}): {}", unit, e.getMessage());
			states.record(CollectionKind.AIR_STATIONS, unit, now, null, Result.FAILURE, now);
		}
		return fallback;
	}

	/** WGS84 → 에어코리아 TM. 변환은 PostGIS(PROJ)의 공식 정의를 쓴다. 결과는 저장하지 않는다. */
	public TmPoint toAirKoreaTm(GeoPoint point) {
		return jdbc.sql("""
				SELECT ST_X(p) AS x, ST_Y(p) AS y
				FROM (SELECT ST_Transform(ST_SetSRID(ST_MakePoint(:lon, :lat), 4326), :srid) AS p) t
				""")
			.param("lon", point.longitude())
			.param("lat", point.latitude())
			.param("srid", AIRKOREA_TM_SRID)
			.query((rs, n) -> new TmPoint(rs.getDouble("x"), rs.getDouble("y")))
			.single();
	}

}
