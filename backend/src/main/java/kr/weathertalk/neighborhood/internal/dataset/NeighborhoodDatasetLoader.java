package kr.weathertalk.neighborhood.internal.dataset;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import kr.weathertalk.neighborhood.ForecastGrid;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 동네 기준 데이터 적재기 (02 §3.2, BR-NBR-03, ADR 0016). 버전 단위로 통째 적재한다.
 * <p>
 * 입력 형식:
 * <ul>
 * <li>행정동 CSV(UTF-8, 머리글 필수) — 기상청 「격자_위경도」 변환본 형식
 * {@code code,sido_name,sigungu_name,eupmyeondong_name,grid_nx,grid_ny,longitude,latitude[,location_updated]}.
 * 대표 좌표·예보 격자는 파일 값을 그대로 쓴다. 격자를 비우면 대표 좌표로 계산하고, 파일 격자가 계산식 결과와 다르면
 * 비교 보고서에 경고로만 남긴다(파일 값 우선).</li>
 * <li>경계 GeoJSON(선택): FeatureCollection, {@code properties.code}가 CSV의 code, geometry는 WGS84 Polygon/MultiPolygon.
 * 일부 동네에만 있어도 된다. 경계가 있는 동네는 포함 검사로, 없는 동네는 최근접 대표점으로 판정한다.
 * 대표 좌표가 비었는데 경계가 있으면 경계 안의 점(ST_PointOnSurface)을 대표 좌표로 쓴다.</li>
 * </ul>
 * 규칙: 코드 중복·잘못된 도형·CSV에 없는 경계·대표 좌표를 정할 수 없는 행이 있으면 아무것도 바꾸지 않고 실패한다.
 * 이번 버전에 없는 기존 동네는 폐지(RETIRED)로 바꾼다(삭제하지 않음 — 과거 기록이 동네 ID를 참조).
 * 동네 ID = 행정동 코드라 같은 코드는 언제나 한 행이다(유일 제약, legacy 04-#26). 같은 입력을 다시 적재해도 결과가 같다(멱등, legacy 04-#25).
 * 동시에 두 적재가 돌지 않도록 트랜잭션 잠금을 건다.
 */
@Component
public class NeighborhoodDatasetLoader {

	private static final Pattern CODE = Pattern.compile("[0-9]{2,10}");
	private static final long LOAD_LOCK_KEY = 0x4e42524c4f4144L; // "NBRLOAD"

	private record CodeRow(String code, String sido, String sigungu, String eupmyeondong, ForecastGrid grid,
			Double latitude, Double longitude) {
	}

	private record Existing(String sido, String sigungu, String eupmyeondong, double latitude, double longitude,
			int nx, int ny, boolean valid, boolean hasBoundary) {
	}

	private final JdbcClient jdbc;
	private final NamedParameterJdbcTemplate batch;
	private final JsonMapper json;
	private final Clock clock;

	NeighborhoodDatasetLoader(JdbcClient jdbc, NamedParameterJdbcTemplate batch, JsonMapper json, Clock clock) {
		this.jdbc = jdbc;
		this.batch = batch;
		this.json = json;
		this.clock = clock;
	}

	/** 경계 없이 대표점만으로 적재한다(기상청 자료 기본 형식). */
	@Transactional
	public DatasetLoadReport load(String version, Reader codesCsv) {
		return load(version, codesCsv, null);
	}

	@Transactional
	public DatasetLoadReport load(String version, Reader codesCsv, Reader boundariesGeoJson) {
		if (version == null || version.isBlank() || version.length() > 40) {
			throw new IllegalArgumentException("데이터 버전은 1~40자여야 합니다.");
		}
		Map<String, CodeRow> rows = parseCsv(codesCsv);
		Map<String, String> geometries = boundariesGeoJson == null ? Map.of() : parseGeoJson(boundariesGeoJson);
		for (String code : geometries.keySet()) {
			if (!rows.containsKey(code)) {
				throw new IllegalArgumentException("GeoJSON에 CSV에 없는 행정동 코드가 있습니다: " + code);
			}
		}
		for (CodeRow row : rows.values()) {
			if (row.latitude() == null && !geometries.containsKey(row.code())) {
				throw new IllegalArgumentException("대표 좌표도 경계도 없는 동네입니다: " + row.code());
			}
		}

		jdbc.sql("SELECT pg_advisory_xact_lock(:key)").param("key", LOAD_LOCK_KEY).query((rs, n) -> 1).single();
		Map<String, Existing> existing = loadExisting();

		List<String> added = new ArrayList<>();
		List<String> changed = new ArrayList<>();
		List<String> gridWarnings = new ArrayList<>();
		List<MapSqlParameterSource> upserts = new ArrayList<>(rows.size());
		for (CodeRow row : rows.values()) {
			String geometry = geometries.get(row.code());
			double lat;
			double lon;
			if (geometry != null) {
				validateGeometry(row.code(), geometry);
			}
			if (row.latitude() != null) {
				lat = row.latitude();
				lon = row.longitude();
			}
			else {
				double[] point = pointOnSurface(geometry);
				lat = point[0];
				lon = point[1];
			}
			ForecastGrid computed = ForecastGridConverter.toGrid(lat, lon);
			ForecastGrid grid = row.grid() != null ? row.grid() : computed;
			if (!grid.equals(computed)) {
				gridWarnings.add(row.code() + " 파일(" + grid.nx() + "," + grid.ny() + ") 계산(" + computed.nx() + ","
						+ computed.ny() + ")");
			}

			Existing before = existing.get(row.code());
			if (before == null) {
				added.add(row.code());
			}
			else if (!unchanged(before, row, lat, lon, grid, geometry)) {
				changed.add(row.code());
			}
			upserts.add(new MapSqlParameterSource().addValue("code", row.code())
				.addValue("sido", row.sido())
				.addValue("sigungu", row.sigungu())
				.addValue("emd", row.eupmyeondong())
				.addValue("g", geometry, Types.VARCHAR)
				.addValue("lat", lat)
				.addValue("lon", lon)
				.addValue("nx", grid.nx())
				.addValue("ny", grid.ny())
				.addValue("v", version));
		}

		Timestamp now = Timestamp.from(clock.instant());
		jdbc.sql("INSERT INTO nbr_dataset_version (version, loaded_at, total_count, added_count, changed_count, retired_count) "
				+ "VALUES (:v, :at, 0, 0, 0, 0) ON CONFLICT (version) DO UPDATE SET loaded_at = EXCLUDED.loaded_at")
			.param("v", version)
			.param("at", now)
			.update();
		batch.batchUpdate("""
				INSERT INTO nbr_neighborhood (code, sido_name, sigungu_name, eupmyeondong_name, boundary,
				    representative_lat, representative_lon, representative_point, grid_nx, grid_ny, status, dataset_version)
				VALUES (:code, :sido, :sigungu, :emd,
				    CASE WHEN CAST(:g AS text) IS NULL THEN NULL
				         ELSE ST_Multi(ST_SetSRID(ST_GeomFromGeoJSON(CAST(:g AS text)), 4326)) END,
				    :lat, :lon, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography, :nx, :ny, 'VALID', :v)
				ON CONFLICT (code) DO UPDATE SET sido_name = EXCLUDED.sido_name, sigungu_name = EXCLUDED.sigungu_name,
				    eupmyeondong_name = EXCLUDED.eupmyeondong_name, boundary = EXCLUDED.boundary,
				    representative_lat = EXCLUDED.representative_lat, representative_lon = EXCLUDED.representative_lon,
				    representative_point = EXCLUDED.representative_point,
				    grid_nx = EXCLUDED.grid_nx, grid_ny = EXCLUDED.grid_ny, status = 'VALID',
				    dataset_version = EXCLUDED.dataset_version
				""", upserts.toArray(MapSqlParameterSource[]::new));

		List<String> retired = jdbc
			.sql("UPDATE nbr_neighborhood SET status = 'RETIRED', dataset_version = :v "
					+ "WHERE status = 'VALID' AND NOT (code = ANY(:codes)) RETURNING code")
			.param("v", version)
			.param("codes", rows.keySet().toArray(String[]::new))
			.query(String.class)
			.list();

		jdbc.sql("UPDATE nbr_dataset_version SET total_count = :t, added_count = :a, changed_count = :c, retired_count = :r "
				+ "WHERE version = :v")
			.param("t", rows.size())
			.param("a", added.size())
			.param("c", changed.size())
			.param("r", retired.size())
			.param("v", version)
			.update();
		return new DatasetLoadReport(version, rows.size(), List.copyOf(added), List.copyOf(changed),
				retired.stream().sorted().toList(), List.copyOf(gridWarnings));
	}

	private Map<String, Existing> loadExisting() {
		Map<String, Existing> result = new HashMap<>();
		jdbc.sql("SELECT code, sido_name, sigungu_name, eupmyeondong_name, representative_lat, representative_lon, "
				+ "grid_nx, grid_ny, status, boundary IS NOT NULL AS has_boundary FROM nbr_neighborhood")
			.query(rs -> {
				result.put(rs.getString("code"),
						new Existing(rs.getString("sido_name"), rs.getString("sigungu_name"),
								rs.getString("eupmyeondong_name"), rs.getDouble("representative_lat"),
								rs.getDouble("representative_lon"), rs.getInt("grid_nx"), rs.getInt("grid_ny"),
								"VALID".equals(rs.getString("status")), rs.getBoolean("has_boundary")));
			});
		return result;
	}

	private boolean unchanged(Existing before, CodeRow row, double lat, double lon, ForecastGrid grid,
			String geometry) {
		boolean same = before.valid() && before.sido().equals(row.sido()) && before.sigungu().equals(row.sigungu())
				&& before.eupmyeondong().equals(row.eupmyeondong()) && before.latitude() == lat
				&& before.longitude() == lon && before.nx() == grid.nx() && before.ny() == grid.ny()
				&& before.hasBoundary() == (geometry != null);
		if (!same || geometry == null) {
			return same;
		}
		return Boolean.TRUE.equals(jdbc
			.sql("SELECT ST_Equals(boundary, ST_Multi(ST_SetSRID(ST_GeomFromGeoJSON(:g), 4326))) FROM nbr_neighborhood "
					+ "WHERE code = :code")
			.param("g", geometry)
			.param("code", row.code())
			.query(Boolean.class)
			.single());
	}

	private void validateGeometry(String code, String geometry) {
		Boolean valid = jdbc.sql("SELECT ST_IsValid(ST_SetSRID(ST_GeomFromGeoJSON(:g), 4326))")
			.param("g", geometry)
			.query(Boolean.class)
			.single();
		if (!Boolean.TRUE.equals(valid)) {
			throw new IllegalArgumentException("경계 도형이 올바르지 않습니다: " + code);
		}
	}

	private double[] pointOnSurface(String geometry) {
		return jdbc
			.sql("SELECT ST_Y(p) AS lat, ST_X(p) AS lon FROM (SELECT ST_PointOnSurface(ST_SetSRID(ST_GeomFromGeoJSON(:g), 4326)) AS p) s")
			.param("g", Objects.requireNonNull(geometry))
			.query((rs, n) -> new double[] { rs.getDouble("lat"), rs.getDouble("lon") })
			.single();
	}

	private static Map<String, CodeRow> parseCsv(Reader reader) {
		Map<String, CodeRow> rows = new LinkedHashMap<>();
		try (BufferedReader in = new BufferedReader(reader)) {
			String header = in.readLine();
			if (header == null) {
				throw new IllegalArgumentException("CSV가 비어 있습니다.");
			}
			List<String> columns = split(header.replace("﻿", ""));
			Map<String, Integer> index = new HashMap<>();
			for (int i = 0; i < columns.size(); i++) {
				index.put(columns.get(i), i);
			}
			for (String required : List.of("code", "sido_name", "sigungu_name", "eupmyeondong_name")) {
				if (!index.containsKey(required)) {
					throw new IllegalArgumentException("CSV 머리글에 " + required + " 열이 없습니다.");
				}
			}
			String line;
			int lineNo = 1;
			while ((line = in.readLine()) != null) {
				lineNo++;
				if (line.isBlank()) {
					continue;
				}
				List<String> cells = split(line);
				String code = cell(cells, index.get("code"));
				if (!CODE.matcher(code).matches()) {
					throw new IllegalArgumentException("CSV " + lineNo + "행: 행정동 코드 형식이 틀립니다.");
				}
				String nx = cell(cells, index.get("grid_nx"));
				String ny = cell(cells, index.get("grid_ny"));
				ForecastGrid grid = nx.isEmpty() || ny.isEmpty() ? null
						: new ForecastGrid(Integer.parseInt(nx), Integer.parseInt(ny));
				String lat = cell(cells, index.get("latitude"));
				String lon = cell(cells, index.get("longitude"));
				Double latitude = null;
				Double longitude = null;
				if (!lat.isEmpty() && !lon.isEmpty()) {
					latitude = Double.parseDouble(lat);
					longitude = Double.parseDouble(lon);
					if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
						throw new IllegalArgumentException("CSV " + lineNo + "행: 대표 좌표가 범위를 벗어났습니다.");
					}
				}
				CodeRow row = new CodeRow(code, required(cells, index.get("sido_name"), lineNo),
						required(cells, index.get("sigungu_name"), lineNo),
						required(cells, index.get("eupmyeondong_name"), lineNo), grid, latitude, longitude);
				if (rows.putIfAbsent(code, row) != null) {
					throw new IllegalArgumentException("CSV에 행정동 코드가 중복됩니다: " + code);
				}
			}
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		if (rows.isEmpty()) {
			throw new IllegalArgumentException("CSV에 동네가 없습니다.");
		}
		return rows;
	}

	private Map<String, String> parseGeoJson(Reader reader) {
		JsonNode root = json.readTree(reader);
		if (!"FeatureCollection".equals(root.path("type").asString(""))) {
			throw new IllegalArgumentException("GeoJSON은 FeatureCollection이어야 합니다.");
		}
		Map<String, String> geometries = new LinkedHashMap<>();
		for (JsonNode feature : root.path("features")) {
			String code = feature.path("properties").path("code").asString("");
			JsonNode geometry = feature.path("geometry");
			String type = geometry.path("type").asString("");
			if (!type.equals("Polygon") && !type.equals("MultiPolygon")) {
				throw new IllegalArgumentException("경계는 Polygon 또는 MultiPolygon이어야 합니다: " + code);
			}
			if (geometries.putIfAbsent(code, json.writeValueAsString(geometry)) != null) {
				throw new IllegalArgumentException("GeoJSON에 행정동 코드가 중복됩니다: " + code);
			}
		}
		return geometries;
	}

	private static List<String> split(String line) {
		List<String> cells = new ArrayList<>();
		for (String cell : line.split(",", -1)) {
			String trimmed = cell.strip();
			if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
				trimmed = trimmed.substring(1, trimmed.length() - 1).strip();
			}
			cells.add(trimmed);
		}
		return cells;
	}

	private static String cell(List<String> cells, Integer index) {
		return index == null || index >= cells.size() ? "" : cells.get(index);
	}

	private static String required(List<String> cells, Integer index, int lineNo) {
		String value = cell(cells, index);
		if (value.isEmpty() || value.length() > 40) {
			throw new IllegalArgumentException("CSV " + lineNo + "행: 이름이 비었거나 40자를 넘습니다.");
		}
		return value;
	}

}
