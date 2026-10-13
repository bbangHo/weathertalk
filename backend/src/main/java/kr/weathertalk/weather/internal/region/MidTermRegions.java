package kr.weathertalk.weather.internal.region;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import kr.weathertalk.shared.Ids.NeighborhoodId;

/**
 * 동네 → 중기 예보구역(육상 구역, 기온 지점) 대응표 (FR-WEA-03, 03 §6.2 "대응표는 weather가 보유").
 * 행정동 코드 접두어(시도 2자리, 시군구 5자리)로 가장 긴 접두어가 이긴다. 원본: classpath weather/mid-term-regions.csv.
 */
@Component
public class MidTermRegions {

	static final String RESOURCE = "weather/mid-term-regions.csv";

	/** @param landRegionId getMidLandFcst regId, @param temperatureRegionId getMidTa regId */
	public record MidTermRegion(String landRegionId, String temperatureRegionId) {
	}

	private record Row(String prefix, MidTermRegion region) {
	}

	private final List<Row> rows;

	public MidTermRegions() {
		this.rows = load();
	}

	public Optional<MidTermRegion> find(NeighborhoodId neighborhood) {
		return rows.stream().filter(row -> neighborhood.value().startsWith(row.prefix())).findFirst().map(Row::region);
	}

	public Set<String> landRegionIds() {
		Set<String> ids = new TreeSet<>();
		rows.forEach(row -> ids.add(row.region().landRegionId()));
		return ids;
	}

	public Set<String> temperatureRegionIds() {
		Set<String> ids = new TreeSet<>();
		rows.forEach(row -> ids.add(row.region().temperatureRegionId()));
		return ids;
	}

	private static List<Row> load() {
		List<Row> result = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(new ClassPathResource(RESOURCE).getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			boolean header = true;
			while ((line = reader.readLine()) != null) {
				if (header) {
					header = false;
					continue;
				}
				if (line.isBlank() || line.startsWith("#")) {
					continue;
				}
				String[] cols = line.split(",", -1);
				result.add(new Row(cols[0].strip(), new MidTermRegion(cols[1].strip(), cols[2].strip())));
			}
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		// 가장 긴 접두어가 먼저 맞게
		result.sort(Comparator.comparingInt((Row row) -> row.prefix().length()).reversed());
		return List.copyOf(result);
	}

}
