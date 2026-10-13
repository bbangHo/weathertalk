package kr.weathertalk.weather.internal.source;

import static kr.weathertalk.weather.internal.source.HttpSourceSupport.text;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.web.client.RestClient;

import kr.weathertalk.neighborhood.ForecastGrid;
import kr.weathertalk.shared.time.TimeConfig;
import kr.weathertalk.weather.internal.config.WeatherSourceProperties;
import kr.weathertalk.weather.internal.conversion.KmaCodes;
import tools.jackson.databind.JsonNode;

/**
 * 기상청 HTTP 어댑터 (공공데이터포털 / API허브, weather.source.kma.provider). 응답 형식은 JSON(dataType=JSON).
 * 결과 코드 03(NODATA_ERROR)·빈 목록은 발표분 미제공, 그 밖의 결과 코드·형식 오류는 실패(BR-WEA-06).
 */
public class KmaHttpSource implements KmaSource {

	static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
	static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmm");
	/** 자외선 시각 인자는 yyyyMMddHH, 시는 두 자리 0 채움(legacy 04-#11). */
	static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyyMMddHH");
	static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

	private static final String NO_DATA = "03";
	private static final int SHORT_TERM_PAGE_SIZE = 1000;
	private static final int UV_MAX_HOUR = 75;
	private static final int UV_STEP = 3;
	private static final int MID_FIRST_DAY = 3;
	private static final int MID_LAST_DAY = 10;

	private final WeatherSourceProperties.Kma properties;
	private final RestClient client;
	private final ExternalCalls calls;

	public KmaHttpSource(WeatherSourceProperties.Kma properties, RestClient client, ExternalCalls calls) {
		this.properties = properties;
		this.client = client;
		this.calls = calls;
	}

	@Override
	public boolean configured() {
		return properties.configured();
	}

	@Override
	public SourceData.Observation fetchObservation(ForecastGrid grid, Instant issuedAt) {
		ZonedDateTime base = issuedAt.atZone(TimeConfig.SEOUL);
		JsonNode items = items("기상청 초단기실황", "/VilageFcstInfoService_2.0/getUltraSrtNcst",
				gridParams(grid, base, 1, 100)).items();
		Map<String, String> values = new LinkedHashMap<>();
		for (JsonNode item : items) {
			values.put(text(item.path("category")), text(item.path("obsrValue")));
		}
		return new SourceData.Observation(issuedAt, KmaCodes.number(values.get("T1H")),
				KmaCodes.number(values.get("REH")), KmaCodes.number(values.get("WSD")),
				KmaCodes.amount(values.get("RN1"), "mm"),
				KmaCodes.observationPrecipitationType(values.get("PTY")));
	}

	@Override
	public List<SourceData.ShortTermSlot> fetchShortTerm(ForecastGrid grid, Instant issuedAt) {
		ZonedDateTime base = issuedAt.atZone(TimeConfig.SEOUL);
		// 날짜+시각을 함께 키로 묶는다(시각만 쓰면 다른 날 값이 섞인다, legacy 04-#23)
		Map<LocalDateTime, Map<String, String>> slots = new TreeMap<>();
		int page = 1;
		int received = 0;
		while (true) {
			Page result = items("기상청 단기예보", "/VilageFcstInfoService_2.0/getVilageFcst",
					gridParams(grid, base, page, SHORT_TERM_PAGE_SIZE));
			for (JsonNode item : result.items()) {
				LocalDateTime at = LocalDateTime.of(LocalDate.parse(text(item.path("fcstDate")), DATE),
						java.time.LocalTime.parse(text(item.path("fcstTime")), TIME));
				slots.computeIfAbsent(at, k -> new LinkedHashMap<>())
					.put(text(item.path("category")), text(item.path("fcstValue")));
				received++;
			}
			if (received >= result.totalCount() || result.items().isEmpty()) {
				break;
			}
			page++;
		}
		List<SourceData.ShortTermSlot> result = new ArrayList<>();
		slots.forEach((at, v) -> result.add(new SourceData.ShortTermSlot(at.atZone(TimeConfig.SEOUL).toInstant(),
				KmaCodes.number(v.get("TMP")), KmaCodes.number(v.get("REH")), KmaCodes.number(v.get("WSD")),
				KmaCodes.integer(v.get("POP")), KmaCodes.shortTermPrecipitationType(v.get("PTY")),
				KmaCodes.amount(v.get("PCP"), "mm"), KmaCodes.amount(v.get("SNO"), "cm"), KmaCodes.sky(v.get("SKY")),
				KmaCodes.number(v.get("TMX")), KmaCodes.number(v.get("TMN")))));
		return result;
	}

	@Override
	public List<SourceData.MidLandDay> fetchMidLand(String regionId, Instant issuedAt) {
		ZonedDateTime base = issuedAt.atZone(TimeConfig.SEOUL);
		JsonNode item = first(items("기상청 중기육상예보", "/MidFcstInfoService/getMidLandFcst", midParams(regionId, base)));
		List<SourceData.MidLandDay> days = new ArrayList<>();
		for (int n = MID_FIRST_DAY; n <= MID_LAST_DAY; n++) {
			String amWf = firstText(item, "wf" + n + "Am", "wf" + n);
			String pmWf = firstText(item, "wf" + n + "Pm", "wf" + n);
			String amPop = firstText(item, "rnSt" + n + "Am", "rnSt" + n);
			String pmPop = firstText(item, "rnSt" + n + "Pm", "rnSt" + n);
			if (amWf == null && pmWf == null && amPop == null && pmPop == null) {
				continue;
			}
			days.add(new SourceData.MidLandDay(base.toLocalDate().plusDays(n), KmaCodes.midTermSky(amWf),
					KmaCodes.midTermSky(pmWf), KmaCodes.integer(amPop), KmaCodes.integer(pmPop),
					KmaCodes.midTermPrecipitationType(amWf), KmaCodes.midTermPrecipitationType(pmWf)));
		}
		if (days.isEmpty()) {
			throw new SourceException.NotReady("기상청 중기육상예보 값 없음");
		}
		return days;
	}

	@Override
	public List<SourceData.MidTemperatureDay> fetchMidTemperature(String regionId, Instant issuedAt) {
		ZonedDateTime base = issuedAt.atZone(TimeConfig.SEOUL);
		JsonNode item = first(items("기상청 중기기온", "/MidFcstInfoService/getMidTa", midParams(regionId, base)));
		List<SourceData.MidTemperatureDay> days = new ArrayList<>();
		for (int n = MID_FIRST_DAY; n <= MID_LAST_DAY; n++) {
			Double min = KmaCodes.number(text(item.path("taMin" + n)));
			Double max = KmaCodes.number(text(item.path("taMax" + n)));
			if (min == null && max == null) {
				continue;
			}
			days.add(new SourceData.MidTemperatureDay(base.toLocalDate().plusDays(n), min, max));
		}
		if (days.isEmpty()) {
			throw new SourceException.NotReady("기상청 중기기온 값 없음");
		}
		return days;
	}

	@Override
	public List<SourceData.UvValue> fetchUv(String areaNo, Instant issuedAt) {
		ZonedDateTime base = issuedAt.atZone(TimeConfig.SEOUL);
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("pageNo", 1);
		params.put("numOfRows", 10);
		params.put("dataType", "JSON");
		params.put("areaNo", areaNo);
		params.put("time", HOUR.format(base));
		JsonNode item = first(items("기상청 자외선지수", "/LivingWthrIdxServiceV4/getUVIdxV4", params));
		String date = text(item.path("date"));
		Instant issued = date == null ? issuedAt
				: LocalDateTime.parse(date, HOUR).atZone(TimeConfig.SEOUL).toInstant();
		List<SourceData.UvValue> values = new ArrayList<>();
		for (int h = 0; h <= UV_MAX_HOUR; h += UV_STEP) {
			JsonNode node = item.path("h" + h);
			if (node.isMissingNode()) {
				continue;
			}
			values.add(new SourceData.UvValue(issued.plusSeconds(h * 3600L), KmaCodes.integer(text(node))));
		}
		if (values.stream().allMatch(v -> v.value() == null)) {
			throw new SourceException.NotReady("기상청 자외선지수 값 없음");
		}
		return values;
	}

	private Map<String, Object> gridParams(ForecastGrid grid, ZonedDateTime base, int page, int rows) {
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("pageNo", page);
		params.put("numOfRows", rows);
		params.put("dataType", "JSON");
		params.put("base_date", DATE.format(base));
		params.put("base_time", TIME.format(base));
		params.put("nx", grid.nx());
		params.put("ny", grid.ny());
		return params;
	}

	private static Map<String, Object> midParams(String regionId, ZonedDateTime base) {
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("pageNo", 1);
		params.put("numOfRows", 10);
		params.put("dataType", "JSON");
		params.put("regId", regionId);
		params.put("tmFc", MINUTE.format(base));
		return params;
	}

	private record Page(JsonNode items, int totalCount) {
	}

	private Page items(String source, String path, Map<String, Object> params) {
		if (!configured()) {
			throw new SourceException.KeyMissing("기상청");
		}
		return calls.call(source, () -> {
			Map<String, Object> withKey = new LinkedHashMap<>(params);
			withKey.put(properties.provider().keyParameter(), properties.serviceKey());
			JsonNode root = HttpSourceSupport.parse(source, HttpSourceSupport.get(client, path, withKey));
			JsonNode response = root.path("response");
			String code = text(response.path("header").path("resultCode"));
			if (NO_DATA.equals(code)) {
				throw new SourceException.NotReady(source + " 발표분 미제공");
			}
			if (!"00".equals(code)) {
				throw new SourceException.Failure(source + " 결과 코드 " + code + " "
						+ text(response.path("header").path("resultMsg")));
			}
			JsonNode body = response.path("body");
			JsonNode items = body.path("items").path("item");
			if (!items.isArray() || items.isEmpty()) {
				throw new SourceException.NotReady(source + " 빈 목록");
			}
			return new Page(items, body.path("totalCount").asInt(items.size()));
		});
	}

	private static JsonNode first(Page page) {
		return page.items().get(0);
	}

	private static String firstText(JsonNode item, String... names) {
		for (String name : names) {
			String value = text(item.path(name));
			if (value != null) {
				return value;
			}
		}
		return null;
	}

}
