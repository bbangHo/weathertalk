package kr.weathertalk.weather;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;

import kr.weathertalk.neighborhood.ForecastGrid;
import kr.weathertalk.shared.time.TimeConfig;
import tools.jackson.databind.json.JsonMapper;

/**
 * 기상청·에어코리아 모의 응답(WireMock). 응답 형식은 각 원천의 JSON 규격(03 §3.1, spec/04 외부 API 표)을 따른다.
 */
public final class WeatherStubs {

	public static final String NCST = "/kma/VilageFcstInfoService_2.0/getUltraSrtNcst";
	public static final String VILAGE = "/kma/VilageFcstInfoService_2.0/getVilageFcst";
	public static final String MID_LAND = "/kma/MidFcstInfoService/getMidLandFcst";
	public static final String MID_TA = "/kma/MidFcstInfoService/getMidTa";
	public static final String UV = "/kma/LivingWthrIdxServiceV4/getUVIdxV4";
	public static final String NEARBY = "/air/MsrstnInfoInqireSvc/getNearbyMsrstnList";
	public static final String MEASURE = "/air/ArpltnInforInqireSvc/getMsrstnAcctoRltmMesureDnsty";

	public static final ForecastGrid N7_GRID = new ForecastGrid(98, 76);

	static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
	static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmm");
	static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyyMMddHH");
	static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
	static final DateTimeFormatter AIR_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final WireMockServer server;

	public WeatherStubs(WireMockServer server) {
		this.server = server;
	}

	/** 날씨 테이블·수집 점유·모의 서버를 비운다. weather 테이블은 날씨 테스트만 쓰므로 통째로 지운다. */
	public static void resetAll(JdbcClient jdbc, StringRedisTemplate redis, WireMockServer server) {
		jdbc.sql("""
				TRUNCATE weather_collect_state, weather_observation, weather_short_term_slot, weather_short_term_issue,
				    weather_mid_land, weather_mid_temperature, weather_air_measurement, weather_air_station_mapping,
				    weather_uv_index, weather_field_report, weather_field_report_precip_publication, weather_precip_episode
				""").update();
		Set<String> keys = redis.keys("weather:collect:*");
		if (keys != null && !keys.isEmpty()) {
			redis.delete(keys);
		}
		server.resetAll();
		// 정의하지 않은 요청은 빠르게 404(근접 불일치 계산 없이) — 원천 장애로 다뤄진다
		server.stubFor(com.github.tomakehurst.wiremock.client.WireMock.any(com.github.tomakehurst.wiremock.client.WireMock.anyUrl())
			.atPriority(100)
			.willReturn(aResponse().withStatus(404)));
	}

	public static Instant kst(String localDateTime) {
		return LocalDateTime.parse(localDateTime).atZone(TimeConfig.SEOUL).toInstant();
	}

	public static ZonedDateTime zoned(Instant instant) {
		return instant.atZone(TimeConfig.SEOUL);
	}

	// ── 기상청 ──

	public static MappingBuilder ncstRequest(ForecastGrid grid, Instant base) {
		return get(urlPathEqualTo(NCST)).withQueryParam("base_date", equalTo(DATE.format(zoned(base))))
			.withQueryParam("base_time", equalTo(TIME.format(zoned(base))))
			.withQueryParam("nx", equalTo(String.valueOf(grid.nx())))
			.withQueryParam("ny", equalTo(String.valueOf(grid.ny())));
	}

	public static RequestPatternBuilder ncstRequested(ForecastGrid grid, Instant base) {
		return getRequestedFor(urlPathEqualTo(NCST)).withQueryParam("base_date", equalTo(DATE.format(zoned(base))))
			.withQueryParam("base_time", equalTo(TIME.format(zoned(base))))
			.withQueryParam("nx", equalTo(String.valueOf(grid.nx())))
			.withQueryParam("ny", equalTo(String.valueOf(grid.ny())));
	}

	public static RequestPatternBuilder vilageRequested(ForecastGrid grid, Instant base) {
		return getRequestedFor(urlPathEqualTo(VILAGE)).withQueryParam("base_date", equalTo(DATE.format(zoned(base))))
			.withQueryParam("base_time", equalTo(TIME.format(zoned(base))))
			.withQueryParam("nx", equalTo(String.valueOf(grid.nx())))
			.withQueryParam("ny", equalTo(String.valueOf(grid.ny())));
	}

	/** 초단기실황. values: 범주 → obsrValue(T1H, REH, WSD, RN1, PTY). */
	public void observation(ForecastGrid grid, Instant base, Map<String, String> values) {
		server.stubFor(ncstRequest(grid, base).willReturn(okJson(kmaItems(values.entrySet()
			.stream()
			.map(e -> item("baseDate", DATE.format(zoned(base)), "baseTime", TIME.format(zoned(base)), "category",
					e.getKey(), "nx", grid.nx(), "ny", grid.ny(), "obsrValue", e.getValue()))
			.toList()))));
	}

	public void observation(ForecastGrid grid, Instant base, double temperature) {
		observation(grid, base, Map.of("T1H", String.valueOf(temperature), "REH", "60", "WSD", "2.0", "RN1", "0",
				"PTY", "0"));
	}

	public static String observationBody(ForecastGrid grid, Instant base, Map<String, String> values) {
		return kmaItems(values.entrySet()
			.stream()
			.map(e -> item("baseDate", DATE.format(zoned(base)), "baseTime", TIME.format(zoned(base)), "category",
					e.getKey(), "nx", grid.nx(), "ny", grid.ny(), "obsrValue", e.getValue()))
			.toList());
	}

	public static Map<String, String> observationValues(double temperature) {
		return Map.of("T1H", String.valueOf(temperature), "REH", "60", "WSD", "2.0", "RN1", "0", "PTY", "0");
	}

	public void observationNoData(ForecastGrid grid, Instant base) {
		server.stubFor(ncstRequest(grid, base).willReturn(okJson(kmaNoData())));
	}

	public void observationResponse(ForecastGrid grid, Instant base, ResponseDefinitionBuilder response) {
		server.stubFor(ncstRequest(grid, base).willReturn(response));
	}

	public static MappingBuilder vilageRequest(ForecastGrid grid, Instant base) {
		return get(urlPathEqualTo(VILAGE)).withQueryParam("base_date", equalTo(DATE.format(zoned(base))))
			.withQueryParam("base_time", equalTo(TIME.format(zoned(base))))
			.withQueryParam("nx", equalTo(String.valueOf(grid.nx())))
			.withQueryParam("ny", equalTo(String.valueOf(grid.ny())));
	}

	/**
	 * 단기예보. 발표 시각 + 1시간부터 count개 칸. slotValues: 칸 시각 → 범주별 값(기본값 위에 덮어씀).
	 */
	public void shortTerm(ForecastGrid grid, Instant base, int count,
			Function<ZonedDateTime, Map<String, String>> slotValues) {
		server.stubFor(vilageRequest(grid, base).willReturn(okJson(shortTermBody(grid, base, count, slotValues))));
	}

	public void shortTerm(ForecastGrid grid, Instant base, int count) {
		shortTerm(grid, base, count, at -> Map.of());
	}

	public static String shortTermBody(ForecastGrid grid, Instant base, int count,
			Function<ZonedDateTime, Map<String, String>> slotValues) {
		List<Map<String, Object>> items = new ArrayList<>();
		ZonedDateTime first = zoned(base).plusHours(1);
		for (int i = 0; i < count; i++) {
			ZonedDateTime at = first.plusHours(i);
			Map<String, String> values = new LinkedHashMap<>();
			values.put("TMP", String.valueOf(10 + at.getHour() % 10));
			values.put("REH", "55");
			values.put("WSD", "1.5");
			values.put("POP", "10");
			values.put("PTY", "0");
			values.put("PCP", "강수없음");
			values.put("SNO", "적설없음");
			values.put("SKY", "1");
			values.putAll(slotValues.apply(at));
			values.forEach((category, value) -> items.add(item("baseDate", DATE.format(zoned(base)), "baseTime",
					TIME.format(zoned(base)), "category", category, "fcstDate", DATE.format(at), "fcstTime",
					TIME.format(at), "fcstValue", value, "nx", grid.nx(), "ny", grid.ny())));
		}
		return kmaItems(items);
	}

	public void shortTermResponse(ForecastGrid grid, Instant base, ResponseDefinitionBuilder response) {
		server.stubFor(vilageRequest(grid, base).willReturn(response));
	}

	public void midLand(String regionId, Instant base, Map<String, Object> fields) {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("regId", regionId);
		item.putAll(fields);
		server.stubFor(get(urlPathEqualTo(MID_LAND)).withQueryParam("regId", equalTo(regionId))
			.withQueryParam("tmFc", equalTo(MINUTE.format(zoned(base))))
			.willReturn(okJson(kmaItems(List.of(item)))));
	}

	public void midTemperature(String regionId, Instant base, Map<String, Object> fields) {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("regId", regionId);
		item.putAll(fields);
		server.stubFor(get(urlPathEqualTo(MID_TA)).withQueryParam("regId", equalTo(regionId))
			.withQueryParam("tmFc", equalTo(MINUTE.format(zoned(base))))
			.willReturn(okJson(kmaItems(List.of(item)))));
	}

	/** 자외선. values: h0, h3, … 순서의 지수. */
	public void uv(String areaNo, Instant base, int... values) {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("code", "A07_2");
		item.put("areaNo", areaNo);
		item.put("date", HOUR.format(zoned(base)));
		for (int i = 0; i < values.length; i++) {
			item.put("h" + (i * 3), String.valueOf(values[i]));
		}
		server.stubFor(get(urlPathEqualTo(UV)).withQueryParam("areaNo", equalTo(areaNo))
			.withQueryParam("time", equalTo(HOUR.format(zoned(base))))
			.willReturn(okJson(kmaItems(List.of(item)))));
	}

	// ── 에어코리아 ──

	public void nearbyStations(String... stations) {
		List<Map<String, Object>> items = new ArrayList<>();
		for (int i = 0; i < stations.length; i++) {
			items.add(item("stationName", stations[i], "addr", "샘플 주소", "tm", 1.0 + i));
		}
		server.stubFor(get(urlPathEqualTo(NEARBY)).willReturn(okJson(airItems(items))));
	}

	/** 측정. rows: [측정 시각(KST), pm10Value, pm25Value, pm10Grade, pm25Grade, o3Grade] */
	public void measurements(String station, List<String[]> rows) {
		List<Map<String, Object>> items = rows.stream()
			.map(r -> item("dataTime", r[0], "stationName", station, "pm10Value", r[1], "pm25Value", r[2],
					"pm10Grade", r[3], "pm25Grade", r[4], "o3Grade", r[5]))
			.toList();
		server.stubFor(get(urlPathEqualTo(MEASURE)).withQueryParam("stationName", equalTo(station))
			.willReturn(okJson(airItems(items))));
	}

	public void measurementsResponse(String station, ResponseDefinitionBuilder response) {
		server.stubFor(get(urlPathEqualTo(MEASURE)).withQueryParam("stationName", equalTo(station))
			.willReturn(response));
	}

	public static String airTime(Instant instant) {
		return AIR_TIME.format(zoned(instant));
	}

	// ── JSON ──

	public static String kmaItems(List<Map<String, Object>> items) {
		return JSON.writeValueAsString(Map.of("response",
				Map.of("header", Map.of("resultCode", "00", "resultMsg", "NORMAL_SERVICE"), "body",
						Map.of("dataType", "JSON", "items", Map.of("item", items), "pageNo", 1, "numOfRows",
								items.size(), "totalCount", items.size()))));
	}

	public static String kmaNoData() {
		return JSON.writeValueAsString(
				Map.of("response", Map.of("header", Map.of("resultCode", "03", "resultMsg", "NO_DATA"))));
	}

	public static String airItems(List<Map<String, Object>> items) {
		return JSON.writeValueAsString(Map.of("response",
				Map.of("header", Map.of("resultCode", "00", "resultMsg", "NORMAL_CODE"), "body",
						Map.of("totalCount", items.size(), "items", items, "pageNo", 1, "numOfRows", 100))));
	}

	public static Map<String, Object> item(Object... keyValues) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i < keyValues.length; i += 2) {
			map.put((String) keyValues[i], keyValues[i + 1]);
		}
		return map;
	}

	public static ResponseDefinitionBuilder serverError() {
		return aResponse().withStatus(500).withBody("error");
	}

}
