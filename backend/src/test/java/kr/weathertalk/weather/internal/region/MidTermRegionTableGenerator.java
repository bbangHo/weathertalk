package kr.weathertalk.weather.internal.region;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.core.io.ClassPathResource;

/**
 * 행정동 → 중기 예보구역 대응표 생성기 (03 §6.2). 기상청 행정동 자료와 공식 중기기온예보구역 코드표를 이름으로 맞춘다.
 * <p>
 * 규칙:
 * <ol>
 * <li>섬 지점(백령도·흑산도·추자도·울릉도·독도)은 읍면동 이름으로 맞춘다(DONG_ALIAS).</li>
 * <li>기온 지점(특성 C)은 시군구 이름으로 맞춘다: "수원시장안구"→수원, "포항시남구"→포항처럼 일반구는 앞의 시 이름,
 * 그 밖에는 시·군·구 접미어를 뗀 이름. 지점 코드 체계로 정해지는 시도 묶음 안에서만 찾는다(경기 광주/광주, 강원 고성/경남 고성 구분).
 * 접미어를 떼기 전 이름이 정확히 같은 지점이 있으면 그것을 우선한다(NAME).</li>
 * <li>못 맞추면 광역시·통합특별시의 자치구는 그 도시 지점(CITY_DISTRICT), 나머지는 시도 대표 지점(SIDO_FALLBACK).</li>
 * <li>육상 구역(특성 A)은 기온 지점 코드로 정한다({@link MidTermRegions#landRegionOf}) — 시도 + 강원 영동/영서 구분.</li>
 * </ol>
 */
final class MidTermRegionTableGenerator {

	static final String DONGS = "neighborhood/kma/kma-administrative-dong-2607.csv";
	static final String OFFICIAL_CODES = "weather/kma/kma-mid-temperature-region-codes-2512.csv";

	static final String HEADER = "code,sido_name,sigungu_name,eupmyeondong_name,land_region_id,land_region_name,"
			+ "temperature_region_id,temperature_region_name,match";

	/** 기온 지점 코드 접두어 → 그 지점을 쓸 수 있는 시도(옛 명칭 포함). */
	private static final Map<String, Set<String>> SIDO_BY_PREFIX = new LinkedHashMap<>();

	static {
		SIDO_BY_PREFIX.put("11A", Set.of("인천광역시"));
		SIDO_BY_PREFIX.put("11B", Set.of("서울특별시", "인천광역시", "경기도"));
		SIDO_BY_PREFIX.put("11C1", Set.of("충청북도"));
		SIDO_BY_PREFIX.put("11C2", Set.of("충청남도", "대전광역시", "세종특별자치시"));
		SIDO_BY_PREFIX.put("11D", Set.of("강원특별자치도", "강원도"));
		SIDO_BY_PREFIX.put("11E", Set.of("경상북도"));
		SIDO_BY_PREFIX.put("11F1", Set.of("전북특별자치도", "전라북도"));
		SIDO_BY_PREFIX.put("21F1", Set.of("전북특별자치도", "전라북도"));
		SIDO_BY_PREFIX.put("11F2", Set.of("전남광주통합특별시", "전라남도", "광주광역시"));
		SIDO_BY_PREFIX.put("21F2", Set.of("전남광주통합특별시", "전라남도", "광주광역시"));
		SIDO_BY_PREFIX.put("11G", Set.of("제주특별자치도"));
		SIDO_BY_PREFIX.put("11H1", Set.of("경상북도", "대구광역시"));
		SIDO_BY_PREFIX.put("11H2", Set.of("경상남도", "부산광역시", "울산광역시"));
	}

	/** 섬 지점: (시군구, 읍면동) → 지점 이름. */
	private static final Map<String, String> DONG_ALIASES = Map.of("옹진군/백령면", "백령도", "신안군/흑산면", "흑산도",
			"제주시/추자면", "추자도", "울릉군/독도", "독도");

	/** 시군구 이름(접미어 뗀 것) → 지점 이름이 다른 경우. */
	private static final Map<String, String> SIGUNGU_ALIASES = Map.of("울릉", "울릉도");

	private static final Pattern GENERAL_DISTRICT = Pattern.compile("^(.+?)시(.+)구$");

	record Point(String code, String name, String kind) {
	}

	record Dong(String code, String sido, String sigungu, String eupmyeondong) {
	}

	enum Match {
		NAME, DONG_ALIAS, CITY_DISTRICT, SIDO_FALLBACK
	}

	record Row(Dong dong, Point land, Point temperature, Match match) {

		String csv() {
			return String.join(",", dong.code(), dong.sido(), dong.sigungu(), dong.eupmyeondong(), land.code(),
					land.name(), temperature.code(), temperature.name(), match.name());
		}

	}

	record Result(List<Row> rows, Map<String, List<String>> ambiguous, Set<String> unusedTemperaturePoints) {

		String csv() {
			StringBuilder out = new StringBuilder(HEADER).append('\n');
			rows.forEach(row -> out.append(row.csv()).append('\n'));
			return out.toString();
		}

		/** 매칭 유형별 시군구 목록(검토용). */
		Map<Match, Set<String>> sigunguByMatch() {
			Map<Match, Set<String>> result = new TreeMap<>();
			for (Row row : rows) {
				result.computeIfAbsent(row.match(), m -> new TreeSet<>())
					.add(row.dong().sido() + " " + row.dong().sigungu()
							+ (row.match() == Match.DONG_ALIAS ? " " + row.dong().eupmyeondong() : "") + " → "
							+ row.temperature().name());
			}
			return result;
		}

	}

	private MidTermRegionTableGenerator() {
	}

	static Result generate() {
		Map<String, Point> points = new LinkedHashMap<>();
		for (String[] cols : csv(OFFICIAL_CODES)) {
			if (cols.length >= 3 && (cols[2].equals("A") || cols[2].equals("C"))) {
				points.put(cols[0], new Point(cols[0], cols[1], cols[2]));
			}
		}
		Map<String, Point> sidoDefaults = new LinkedHashMap<>();
		for (String[] cols : csv(MidTermRegions.SIDO_DEFAULTS)) {
			sidoDefaults.put(cols[0], require(points, cols[1]));
		}

		List<Row> rows = new ArrayList<>();
		Map<String, List<String>> ambiguous = new TreeMap<>();
		Set<String> used = new TreeSet<>();
		for (String[] cols : csv(DONGS)) {
			Dong dong = new Dong(cols[0], cols[1], cols[2], cols[3]);
			Point temperature;
			Match match;
			String alias = DONG_ALIASES.get(dong.sigungu() + "/" + dong.eupmyeondong());
			List<Point> candidates = alias != null ? byName(points, dong.sido(), alias, alias) : List.of();
			if (!candidates.isEmpty()) {
				match = Match.DONG_ALIAS;
			}
			else {
				String normalized = normalize(dong.sigungu());
				candidates = byName(points, dong.sido(), dong.sigungu(),
						SIGUNGU_ALIASES.getOrDefault(normalized, normalized));
				match = Match.NAME;
			}
			if (candidates.size() > 1) {
				ambiguous.put(dong.sido() + " " + dong.sigungu(),
						candidates.stream().map(p -> p.code() + " " + p.name()).toList());
			}
			if (!candidates.isEmpty()) {
				temperature = candidates.getFirst();
			}
			else {
				temperature = sidoDefaults.get(dong.sido());
				if (temperature == null) {
					throw new IllegalStateException("시도 대표 지점이 없습니다: " + dong.sido());
				}
				match = dong.sigungu().endsWith("구") ? Match.CITY_DISTRICT : Match.SIDO_FALLBACK;
			}
			Point land = require(points, MidTermRegions.landRegionOf(temperature.code()));
			used.add(temperature.code());
			rows.add(new Row(dong, land, temperature, match));
		}
		Set<String> unused = new TreeSet<>();
		points.values()
			.stream()
			.filter(p -> p.kind().equals("C") && !used.contains(p.code()))
			.forEach(p -> unused.add(p.code() + " " + p.name()));
		return new Result(List.copyOf(rows), ambiguous, unused);
	}

	/** 시도 묶음 안에서 이름이 같은 기온 지점. 접미어를 떼기 전 이름과 정확히 같은 지점이 앞에 온다. */
	private static List<Point> byName(Map<String, Point> points, String sido, String fullName, String name) {
		List<Point> exact = new ArrayList<>();
		List<Point> stripped = new ArrayList<>();
		for (Point point : points.values()) {
			if (!point.kind().equals("C") || !allowed(point.code(), sido)) {
				continue;
			}
			if (point.name().equals(fullName)) {
				exact.add(point);
			}
			else if (point.name().equals(name)) {
				stripped.add(point);
			}
		}
		exact.addAll(stripped);
		return exact;
	}

	private static boolean allowed(String code, String sido) {
		String longest = null;
		for (String prefix : SIDO_BY_PREFIX.keySet()) {
			if (code.startsWith(prefix) && (longest == null || prefix.length() > longest.length())) {
				longest = prefix;
			}
		}
		return longest != null && SIDO_BY_PREFIX.get(longest).contains(sido);
	}

	/** "수원시장안구"→수원, "세종특별자치시"→세종, "가평군"→가평, "종로구"→종로. */
	static String normalize(String sigungu) {
		Matcher general = GENERAL_DISTRICT.matcher(sigungu);
		if (general.matches()) {
			return general.group(1);
		}
		for (String suffix : List.of("특별자치시", "특별시", "광역시", "시", "군", "구")) {
			if (sigungu.endsWith(suffix) && sigungu.length() > suffix.length() + 1) {
				return sigungu.substring(0, sigungu.length() - suffix.length());
			}
		}
		return sigungu;
	}

	/** 공식 코드표의 코드 → 특성(A 육상, C 기온 지점, H 해양 …). */
	static Map<String, String> officialKinds() {
		Map<String, String> kinds = new LinkedHashMap<>();
		for (String[] cols : csv(OFFICIAL_CODES)) {
			if (cols.length >= 3) {
				kinds.put(cols[0], cols[2]);
			}
		}
		return kinds;
	}

	private static Point require(Map<String, Point> points, String code) {
		Point point = points.get(code);
		if (point == null) {
			throw new IllegalStateException("공식 코드표에 없는 코드입니다: " + code);
		}
		return point;
	}

	private static List<String[]> csv(String resource) {
		List<String[]> rows = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(new ClassPathResource(resource).getInputStream(), StandardCharsets.UTF_8))) {
			reader.lines().skip(1).filter(l -> !l.isBlank() && !l.startsWith("#")).forEach(line -> {
				String[] cols = line.split(",", -1);
				for (int i = 0; i < cols.length; i++) {
					cols[i] = cols[i].strip();
				}
				rows.add(cols);
			});
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return rows;
	}

}
