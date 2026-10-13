package kr.weathertalk.neighborhood.internal.dataset;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import kr.weathertalk.neighborhood.ForecastGrid;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 동네 기준 데이터 적재기 (02 §3.2, BR-NBR-03). 버전 단위로 통째 적재한다.
 * <p>
 * 표준 입력 형식(출처가 정해지면 출처 원본을 이 형식으로 바꿔 넣는다):
 * <ul>
 * <li>행정동 코드 CSV(UTF-8, 머리글 필수): {@code code,sido_name,sigungu_name,eupmyeondong_name,grid_nx,grid_ny}.
 * grid_nx·grid_ny는 기상청 행정구역별 격자 표 값이며, 비우면 대표 좌표로 계산한다(BR-NBR-04).</li>
 * <li>경계 GeoJSON: FeatureCollection, 각 Feature의 {@code properties.code}가 CSV의 code, geometry는 WGS84 Polygon/MultiPolygon.</li>
 * </ul>
 * 규칙: CSV와 GeoJSON의 코드 집합이 같아야 하고 코드 중복·잘못된 도형이 있으면 아무것도 바꾸지 않고 실패한다.
 * 이번 버전에 없는 기존 동네는 폐지(RETIRED)로 바꾼다(삭제하지 않음 — 과거 기록이 동네 ID를 참조).
 * 동네 ID = 행정동 코드라 같은 코드는 언제나 한 행이다(유일 제약, legacy 04-#26). 같은 입력을 다시 적재해도 결과가 같다(멱등, legacy 04-#25).
 * 동시에 두 적재가 돌지 않도록 트랜잭션 잠금을 건다.
 */
@Component
public class NeighborhoodDatasetLoader {

	private static final Pattern CODE = Pattern.compile("[0-9]{2,10}");
	private static final long LOAD_LOCK_KEY = 0x4e42524c4f4144L; // "NBRLOAD"

	record CodeRow(String code, String sido, String sigungu, String eupmyeondong, ForecastGrid grid) {
	}

	private final JdbcClient jdbc;
	private final JsonMapper json;
	private final Clock clock;

	NeighborhoodDatasetLoader(JdbcClient jdbc, JsonMapper json, Clock clock) {
		this.jdbc = jdbc;
		this.json = json;
		this.clock = clock;
	}

	@Transactional
	public DatasetLoadReport load(String version, Reader codesCsv, Reader boundariesGeoJson) {
		if (version == null || version.isBlank() || version.length() > 40) {
			throw new IllegalArgumentException("데이터 버전은 1~40자여야 합니다.");
		}
		Map<String, CodeRow> rows = parseCsv(codesCsv);
		Map<String, String> geometries = parseGeoJson(boundariesGeoJson);
		if (!rows.keySet().equals(geometries.keySet())) {
			throw new IllegalArgumentException("CSV와 GeoJSON의 행정동 코드 집합이 다릅니다.");
		}

		jdbc.sql("SELECT pg_advisory_xact_lock(:key)").param("key", LOAD_LOCK_KEY).query((rs, n) -> 1).single();
		Timestamp now = Timestamp.from(clock.instant());
		jdbc.sql("INSERT INTO nbr_dataset_version (version, loaded_at, total_count, added_count, changed_count, retired_count) "
				+ "VALUES (:v, :at, 0, 0, 0, 0) ON CONFLICT (version) DO UPDATE SET loaded_at = EXCLUDED.loaded_at")
			.param("v", version)
			.param("at", now)
			.update();

		List<String> added = new ArrayList<>();
		List<String> changed = new ArrayList<>();
		for (CodeRow row : rows.values()) {
			String geometry = geometries.get(row.code());
			Boolean valid = jdbc.sql("SELECT ST_IsValid(ST_SetSRID(ST_GeomFromGeoJSON(:g), 4326))")
				.param("g", geometry)
				.query(Boolean.class)
				.single();
			if (!Boolean.TRUE.equals(valid)) {
				throw new IllegalArgumentException("경계 도형이 올바르지 않습니다: " + row.code());
			}
			double[] point = jdbc
				.sql("SELECT ST_Y(p) AS lat, ST_X(p) AS lon FROM (SELECT ST_PointOnSurface(ST_SetSRID(ST_GeomFromGeoJSON(:g), 4326)) AS p) s")
				.param("g", geometry)
				.query((rs, n) -> new double[] { rs.getDouble("lat"), rs.getDouble("lon") })
				.single();
			ForecastGrid grid = row.grid() != null ? row.grid() : ForecastGridConverter.toGrid(point[0], point[1]);

			Optional<Boolean> unchanged = jdbc
				.sql("SELECT (sido_name = :sido AND sigungu_name = :sigungu AND eupmyeondong_name = :emd AND grid_nx = :nx "
						+ "AND grid_ny = :ny AND status = 'VALID' "
						+ "AND ST_Equals(boundary, ST_Multi(ST_SetSRID(ST_GeomFromGeoJSON(:g), 4326)))) "
						+ "FROM nbr_neighborhood WHERE code = :code")
				.param("sido", row.sido())
				.param("sigungu", row.sigungu())
				.param("emd", row.eupmyeondong())
				.param("nx", grid.nx())
				.param("ny", grid.ny())
				.param("g", geometry)
				.param("code", row.code())
				.query(Boolean.class)
				.optional();
			if (unchanged.isEmpty()) {
				added.add(row.code());
			}
			else if (!unchanged.get()) {
				changed.add(row.code());
			}

			jdbc.sql("""
					INSERT INTO nbr_neighborhood (code, sido_name, sigungu_name, eupmyeondong_name, boundary,
					    representative_lat, representative_lon, grid_nx, grid_ny, status, dataset_version)
					VALUES (:code, :sido, :sigungu, :emd, ST_Multi(ST_SetSRID(ST_GeomFromGeoJSON(:g), 4326)),
					    :lat, :lon, :nx, :ny, 'VALID', :v)
					ON CONFLICT (code) DO UPDATE SET sido_name = EXCLUDED.sido_name, sigungu_name = EXCLUDED.sigungu_name,
					    eupmyeondong_name = EXCLUDED.eupmyeondong_name, boundary = EXCLUDED.boundary,
					    representative_lat = EXCLUDED.representative_lat, representative_lon = EXCLUDED.representative_lon,
					    grid_nx = EXCLUDED.grid_nx, grid_ny = EXCLUDED.grid_ny, status = 'VALID',
					    dataset_version = EXCLUDED.dataset_version
					""")
				.param("code", row.code())
				.param("sido", row.sido())
				.param("sigungu", row.sigungu())
				.param("emd", row.eupmyeondong())
				.param("g", geometry)
				.param("lat", point[0])
				.param("lon", point[1])
				.param("nx", grid.nx())
				.param("ny", grid.ny())
				.param("v", version)
				.update();
		}

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
				retired.stream().sorted().toList());
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
				CodeRow row = new CodeRow(code, required(cells, index.get("sido_name"), lineNo),
						required(cells, index.get("sigungu_name"), lineNo),
						required(cells, index.get("eupmyeondong_name"), lineNo), grid);
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
