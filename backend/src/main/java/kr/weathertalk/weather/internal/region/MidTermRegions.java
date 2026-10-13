package kr.weathertalk.weather.internal.region;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import kr.weathertalk.neighborhood.Neighborhood;

/**
 * 동네 → 중기 예보구역(육상 구역, 기온 지점) 대응표 (FR-WEA-03, 03 §6.2 "대응표는 weather가 보유").
 * <p>
 * 원본: 기상청 중기기온예보구역 코드표(classpath weather/kma)와 기상청 행정동 자료를 이름으로 맞춰 생성한
 * {@value #TABLE}(행정동 코드별, 생성·검증은 MidTermRegionTableTests). 찾는 순서:
 * 행정동 코드 일치 → 같은 시군구(코드 앞 5자리)의 대응 → 시도 대표 지점({@value #SIDO_DEFAULTS}).
 * 대응표에 없는 코드는 기준 데이터가 표보다 새 버전일 때 생긴다(표를 다시 생성하면 사라진다).
 */
@Component
public class MidTermRegions {

	static final String TABLE = "weather/neighborhood-mid-term-regions.csv";
	static final String SIDO_DEFAULTS = "weather/mid-term-region-sido-defaults.csv";

	private static final int SIGUNGU_DIGITS = 5;

	/**
	 * 기온 지점 코드 → 육상 구역. 공식 코드표의 코드 체계를 따른다(강원은 영서 11D1·영동 11D2, 울릉도 11E는 경북, 21F는 서해안 전라 지점,
	 * 백령도 11A는 인천). 앞쪽이 더 긴 접두어.
	 */
	private static final List<String[]> LAND_BY_PREFIX = List.of(new String[] { "11C1", "11C10000" },
			new String[] { "11C2", "11C20000" }, new String[] { "11D1", "11D10000" },
			new String[] { "11D2", "11D20000" }, new String[] { "11F1", "11F10000" },
			new String[] { "21F1", "11F10000" }, new String[] { "11F2", "11F20000" },
			new String[] { "21F2", "11F20000" }, new String[] { "11H1", "11H10000" },
			new String[] { "11H2", "11H20000" }, new String[] { "11A", "11B00000" }, new String[] { "11B", "11B00000" },
			new String[] { "11E", "11H10000" }, new String[] { "11G", "11G00000" });

	/** @param landRegionId getMidLandFcst regId, @param temperatureRegionId getMidTa regId */
	public record MidTermRegion(String landRegionId, String temperatureRegionId) {
	}

	private final Map<String, MidTermRegion> byCode = new HashMap<>();
	private final Map<String, MidTermRegion> bySigungu = new HashMap<>();
	private final Map<String, MidTermRegion> bySido = new HashMap<>();

	public MidTermRegions() {
		read(TABLE, cols -> {
			// code,sido_name,sigungu_name,eupmyeondong_name,land_region_id,land_region_name,temperature_region_id,temperature_region_name,match
			MidTermRegion region = new MidTermRegion(cols[4], cols[6]);
			byCode.put(cols[0], region);
			if (!"DONG_ALIAS".equals(cols[8]) && cols[0].length() >= SIGUNGU_DIGITS) {
				bySigungu.putIfAbsent(cols[0].substring(0, SIGUNGU_DIGITS), region);
			}
		});
		read(SIDO_DEFAULTS, cols -> bySido.put(cols[0], new MidTermRegion(landRegionOf(cols[1]), cols[1])));
	}

	public Optional<MidTermRegion> find(Neighborhood neighborhood) {
		String code = neighborhood.id().value();
		MidTermRegion region = byCode.get(code);
		if (region == null && code.length() >= SIGUNGU_DIGITS) {
			region = bySigungu.get(code.substring(0, SIGUNGU_DIGITS));
		}
		if (region == null) {
			region = bySido.get(neighborhood.sidoName());
		}
		return Optional.ofNullable(region);
	}

	/** 정기 수집 대상 육상 구역(대응표와 시도 대표 지점에 쓰인 것). */
	public Set<String> landRegionIds() {
		Set<String> ids = new TreeSet<>();
		byCode.values().forEach(r -> ids.add(r.landRegionId()));
		bySido.values().forEach(r -> ids.add(r.landRegionId()));
		return ids;
	}

	/** 정기 수집 대상 기온 지점(대응표와 시도 대표 지점에 쓰인 것). */
	public Set<String> temperatureRegionIds() {
		Set<String> ids = new TreeSet<>();
		byCode.values().forEach(r -> ids.add(r.temperatureRegionId()));
		bySido.values().forEach(r -> ids.add(r.temperatureRegionId()));
		return ids;
	}

	/** 기온 지점 코드가 속한 중기 육상예보 구역. */
	public static String landRegionOf(String temperatureRegionId) {
		for (String[] entry : LAND_BY_PREFIX) {
			if (temperatureRegionId.startsWith(entry[0])) {
				return entry[1];
			}
		}
		throw new IllegalArgumentException("육상 구역을 정할 수 없는 기온 지점입니다: " + temperatureRegionId);
	}

	private static void read(String resource, Consumer<String[]> row) {
		List<String> lines = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(new ClassPathResource(resource).getInputStream(), StandardCharsets.UTF_8))) {
			reader.lines().skip(1).forEach(lines::add);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		for (String line : lines) {
			if (line.isBlank() || line.startsWith("#")) {
				continue;
			}
			String[] cols = line.split(",", -1);
			for (int i = 0; i < cols.length; i++) {
				cols[i] = cols[i].strip();
			}
			row.accept(cols);
		}
	}

}
