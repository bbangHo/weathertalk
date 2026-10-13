package kr.weathertalk.weather.internal.source;

import static kr.weathertalk.weather.internal.source.HttpSourceSupport.text;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.springframework.web.client.RestClient;

import kr.weathertalk.shared.time.TimeConfig;
import kr.weathertalk.weather.AirGrade;
import kr.weathertalk.weather.internal.config.WeatherSourceProperties;
import kr.weathertalk.weather.internal.conversion.AirGrades;
import kr.weathertalk.weather.internal.conversion.KmaCodes;
import tools.jackson.databind.JsonNode;

/**
 * 에어코리아 HTTP 어댑터 (공공데이터포털, returnType=json). 요청 변수·응답 필드는 「에어코리아 측정소정보 기술문서 v1.2」·
 * 「대기오염정보 기술문서 v1.4」를 따른다. 측정값 "-"는 결측(null)이며 0으로 채우지 않는다(03 §3.3).
 * 에어코리아 자료는 공공누리 제3유형(변경금지)이라 값·등급을 그대로 옮기고, 등급이 비었을 때만 환경부 공식 경계로 판정한다.
 */
public class AirKoreaHttpSource implements AirKoreaSource {

	static final String STATION_LIST = "getMsrstnList";
	static final String REALTIME_BY_SIDO = "getCtprvnRltmMesureDnsty";

	private static final String STATION_LIST_PATH = "/MsrstnInfoInqireSvc/" + STATION_LIST;
	private static final String REALTIME_BY_SIDO_PATH = "/ArpltnInforInqireSvc/" + REALTIME_BY_SIDO;

	/** ver=1.1: dmX=경도, dmY=위도(1.1 이전은 둘이 뒤바뀌어 온다, 측정소정보 기술문서 v1.2). */
	private static final String STATION_LIST_VERSION = "1.1";

	/** ver=1.3: PM2.5·24시간 등급·1시간 등급 포함(대기오염정보 기술문서 v1.4). 기존 측정소별 조회와 같은 판으로 둬 값 해석을 바꾸지 않는다. */
	private static final String REALTIME_VERSION = "1.3";

	/** 결과 코드 03 = 데이터 없음(기술문서 에러 코드). */
	private static final String NO_DATA = "03";

	/**
	 * 좌표 이상 판정용 한국 영역(경도 124~132°, 위도 33~39.5°). 운영 파라미터가 아니라 국토 범위다.
	 * 1.1 이전 판처럼 경도·위도가 뒤바뀌어 오면 바로잡고, 그래도 벗어나면 좌표 없음으로 둔다.
	 */
	private static final double MIN_LON = 124, MAX_LON = 132, MIN_LAT = 33, MAX_LAT = 39.5;

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

	private final WeatherSourceProperties.AirKorea properties;
	private final RestClient client;
	private final ExternalCalls calls;
	private final AirKoreaCallCounter counter;

	public AirKoreaHttpSource(WeatherSourceProperties.AirKorea properties, RestClient client, ExternalCalls calls,
			AirKoreaCallCounter counter) {
		this.properties = properties;
		this.client = client;
		this.calls = calls;
		this.counter = counter;
	}

	@Override
	public boolean configured() {
		return properties.configured();
	}

	@Override
	public List<SourceData.AirStation> fetchStations() {
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("ver", STATION_LIST_VERSION);
		List<SourceData.AirStation> stations = allPages("에어코리아 측정소 목록", STATION_LIST, STATION_LIST_PATH, params,
				AirKoreaHttpSource::station);
		if (stations.isEmpty()) {
			throw new SourceException.Failure("에어코리아 측정소 목록 빈 목록");
		}
		return stations;
	}

	@Override
	public List<SourceData.StationAirMeasurement> fetchRealtime(String sidoName) {
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("sidoName", sidoName);
		params.put("ver", REALTIME_VERSION);
		List<SourceData.StationAirMeasurement> rows = allPages("에어코리아 시도별 실시간 측정", REALTIME_BY_SIDO,
				REALTIME_BY_SIDO_PATH, params, AirKoreaHttpSource::measurement);
		if (rows.isEmpty()) {
			throw new SourceException.NotReady("에어코리아 시도별 실시간 측정(" + sidoName + ") 빈 목록");
		}
		return rows;
	}

	static SourceData.AirStation station(JsonNode item) {
		String name = text(item.path("stationName"));
		if (name == null) {
			return null;
		}
		Double x = KmaCodes.number(text(item.path("dmX")));
		Double y = KmaCodes.number(text(item.path("dmY")));
		Double lon = null;
		Double lat = null;
		if (x != null && y != null) {
			if (inKorea(x, y)) {
				lon = x;
				lat = y;
			}
			else if (inKorea(y, x)) {
				lon = y;
				lat = x;
			}
		}
		return new SourceData.AirStation(name, text(item.path("addr")), text(item.path("mangName")),
				text(item.path("item")), text(item.path("year")), lon, lat);
	}

	private static boolean inKorea(double lon, double lat) {
		return lon >= MIN_LON && lon <= MAX_LON && lat >= MIN_LAT && lat <= MAX_LAT;
	}

	static SourceData.StationAirMeasurement measurement(JsonNode item) {
		String station = text(item.path("stationName"));
		Instant measuredAt = measuredAt(text(item.path("dataTime")));
		if (station == null || measuredAt == null) {
			return null;
		}
		Double pm10 = KmaCodes.number(text(item.path("pm10Value")));
		Double pm25 = KmaCodes.number(text(item.path("pm25Value")));
		// pm10Grade·pm25Grade = 24시간 등급(기술문서). 비었을 때만 농도로 공식 경계 판정(M2와 같은 해석 — 결과를 바꾸지 않는다)
		AirGrade pm10Grade = grade(text(item.path("pm10Grade")), AirGrades.pm10(pm10));
		AirGrade pm25Grade = grade(text(item.path("pm25Grade")), AirGrades.pm25(pm25));
		return new SourceData.StationAirMeasurement(station, new SourceData.AirMeasurement(measuredAt, pm10, pm25,
				pm10Grade, pm25Grade, grade(text(item.path("o3Grade")), null)));
	}

	/** 등급이 결측이면 농도로 공식 경계 판정한 값을 쓴다. */
	private static AirGrade grade(String code, AirGrade fallback) {
		AirGrade grade = AirGrades.fromCode(KmaCodes.integer(code));
		return grade != null ? grade : fallback;
	}

	/** "yyyy-MM-dd HH:mm". 에어코리아는 자정을 전날 "24:00"으로 준다. */
	static Instant measuredAt(String dataTime) {
		if (dataTime == null || dataTime.length() < 16) {
			return null;
		}
		try {
			LocalDate date = LocalDate.parse(dataTime.substring(0, 10), DATE);
			int hour = Integer.parseInt(dataTime.substring(11, 13));
			int minute = Integer.parseInt(dataTime.substring(14, 16));
			return date.atTime(LocalTime.MIDNIGHT).plusHours(hour).plusMinutes(minute).atZone(TimeConfig.SEOUL)
				.toInstant();
		}
		catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * pageNo 1부터 전체 결과 수(totalCount)만큼 모은다. 페이지 크기는 weather.source.air-korea.page-size.
	 * 중간 페이지가 비어 전체에 못 미치면 실패 — 일부만 받은 목록을 전체로 쓰지 않는다.
	 */
	private <T> List<T> allPages(String source, String operation, String path, Map<String, Object> params,
			Function<JsonNode, T> mapper) {
		int pageSize = properties.pageSize();
		List<T> result = new ArrayList<>();
		int received = 0;
		int pageNo = 1;
		Integer total = null;
		do {
			Page page = page(source, operation, path, params, pageNo, pageSize);
			total = total == null ? page.totalCount() : total;
			if (page.items().isEmpty()) {
				if (received < total) {
					throw new SourceException.Failure(source + " " + pageNo + "쪽이 비어 전체 " + total + "건 중 " + received
							+ "건만 받음");
				}
				break;
			}
			for (JsonNode item : page.items()) {
				T mapped = mapper.apply(item);
				if (mapped != null) {
					result.add(mapped);
				}
			}
			received += page.items().size();
			pageNo++;
		}
		while (received < total);
		return result;
	}

	private record Page(List<JsonNode> items, int totalCount) {
	}

	private Page page(String source, String operation, String path, Map<String, Object> params, int pageNo,
			int pageSize) {
		if (!configured()) {
			throw new SourceException.KeyMissing("에어코리아");
		}
		return calls.call(source, () -> {
			Map<String, Object> query = new LinkedHashMap<>();
			query.put("returnType", "json");
			query.put("numOfRows", pageSize);
			query.put("pageNo", pageNo);
			query.putAll(params);
			query.put("serviceKey", properties.serviceKey());
			counter.record(operation);
			JsonNode root = HttpSourceSupport.parse(source, HttpSourceSupport.get(client, path, query));
			JsonNode response = root.path("response");
			String code = text(response.path("header").path("resultCode"));
			if (NO_DATA.equals(code)) {
				return new Page(List.of(), 0);
			}
			if (!"00".equals(code)) {
				throw new SourceException.Failure(source + " 결과 코드 " + code + " "
						+ text(response.path("header").path("resultMsg")));
			}
			JsonNode body = response.path("body");
			List<JsonNode> items = new ArrayList<>();
			JsonNode node = body.path("items");
			// JSON 응답의 items는 배열이다. XML 변환형({"item": [...]})으로 올 때도 받아 준다
			JsonNode array = node.isArray() ? node : node.path("item");
			if (array.isArray()) {
				array.forEach(items::add);
			}
			else if (array.isObject()) {
				items.add(array);
			}
			Integer total = KmaCodes.integer(text(body.path("totalCount")));
			return new Page(items, total == null ? items.size() : total);
		});
	}

}
