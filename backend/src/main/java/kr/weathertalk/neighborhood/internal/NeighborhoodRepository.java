package kr.weathertalk.neighborhood.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import kr.weathertalk.neighborhood.ForecastGrid;
import kr.weathertalk.neighborhood.GeoPoint;
import kr.weathertalk.neighborhood.Neighborhood;
import kr.weathertalk.neighborhood.NeighborhoodStatus;
import kr.weathertalk.shared.Ids.NeighborhoodId;

/**
 * 동네 기준 데이터 조회. 좌표로 동네를 판정하는 일은 서버가 하지 않는다 — 앱이 기기 안에서 대표점 최근접으로 판정한다(ADR 0009).
 */
@Repository
public class NeighborhoodRepository {

	static final String COLUMNS = "code, sido_name, sigungu_name, eupmyeondong_name, representative_lat, "
			+ "representative_lon, grid_nx, grid_ny, status";

	private final JdbcClient jdbc;

	NeighborhoodRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	Optional<Neighborhood> findById(String code) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM nbr_neighborhood WHERE code = :code")
			.param("code", code)
			.query(NeighborhoodRepository::map)
			.optional();
	}

	List<Neighborhood> findByIds(Collection<String> codes) {
		if (codes.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("SELECT " + COLUMNS + " FROM nbr_neighborhood WHERE code IN (:codes)")
			.param("codes", codes)
			.query(NeighborhoodRepository::map)
			.list();
	}

	/** 이름 일부 검색. 읍면동 이름 완전 일치 → 읍면동 이름 포함 → 시군구·시도 이름 포함 순. */
	List<Neighborhood> search(String query, int limit) {
		String escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
		return jdbc.sql("SELECT " + COLUMNS + " FROM nbr_neighborhood WHERE status = 'VALID' AND ("
				+ "eupmyeondong_name ILIKE :like OR sigungu_name ILIKE :like OR sido_name ILIKE :like "
				+ "OR (sido_name || ' ' || sigungu_name || ' ' || eupmyeondong_name) ILIKE :like) "
				+ "ORDER BY CASE WHEN eupmyeondong_name = :q THEN 0 WHEN eupmyeondong_name ILIKE :like THEN 1 ELSE 2 END, "
				+ "sido_name, sigungu_name, eupmyeondong_name LIMIT :limit")
			.param("like", "%" + escaped + "%")
			.param("q", query)
			.param("limit", limit)
			.query(NeighborhoodRepository::map)
			.list();
	}

	/** 단계 선택: 시도 목록 / 시도의 시군구 목록. */
	public List<String> sidoNames() {
		return jdbc.sql("SELECT DISTINCT sido_name FROM nbr_neighborhood WHERE status = 'VALID' ORDER BY sido_name")
			.query(String.class)
			.list();
	}

	public List<String> sigunguNames(String sido) {
		return jdbc
			.sql("SELECT DISTINCT sigungu_name FROM nbr_neighborhood WHERE status = 'VALID' AND sido_name = :sido "
					+ "ORDER BY sigungu_name")
			.param("sido", sido)
			.query(String.class)
			.list();
	}

	/** 단계 선택: 시군구의 읍면동(동네) 목록. */
	public List<Neighborhood> inSigungu(String sido, String sigungu) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM nbr_neighborhood WHERE status = 'VALID' AND sido_name = :sido "
				+ "AND sigungu_name = :sigungu ORDER BY eupmyeondong_name")
			.param("sido", sido)
			.param("sigungu", sigungu)
			.query(NeighborhoodRepository::map)
			.list();
	}

	static Neighborhood map(ResultSet rs, int rowNum) throws SQLException {
		return new Neighborhood(new NeighborhoodId(rs.getString("code")), rs.getString("sido_name"),
				rs.getString("sigungu_name"), rs.getString("eupmyeondong_name"),
				new GeoPoint(rs.getDouble("representative_lat"), rs.getDouble("representative_lon")),
				new ForecastGrid(rs.getInt("grid_nx"), rs.getInt("grid_ny")),
				NeighborhoodStatus.valueOf(rs.getString("status")));
	}

}
