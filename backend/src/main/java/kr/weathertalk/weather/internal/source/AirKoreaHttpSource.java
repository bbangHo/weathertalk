package kr.weathertalk.weather.internal.source;

import static kr.weathertalk.weather.internal.source.HttpSourceSupport.text;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.client.RestClient;

import kr.weathertalk.shared.time.TimeConfig;
import kr.weathertalk.weather.AirGrade;
import kr.weathertalk.weather.internal.config.WeatherSourceProperties;
import kr.weathertalk.weather.internal.conversion.AirGrades;
import kr.weathertalk.weather.internal.conversion.KmaCodes;
import tools.jackson.databind.JsonNode;

/**
 * 에어코리아 HTTP 어댑터 (공공데이터포털, returnType=json). 측정값 "-"는 결측(null)이며 0으로 채우지 않는다(03 §3.3).
 */
public class AirKoreaHttpSource implements AirKoreaSource {

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
	private static final int MEASUREMENT_ROWS = 100;

	private final WeatherSourceProperties.AirKorea properties;
	private final RestClient client;
	private final ExternalCalls calls;

	public AirKoreaHttpSource(WeatherSourceProperties.AirKorea properties, RestClient client, ExternalCalls calls) {
		this.properties = properties;
		this.client = client;
		this.calls = calls;
	}

	@Override
	public boolean configured() {
		return properties.configured();
	}

	@Override
	public List<String> findNearbyStations(double tmX, double tmY) {
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("returnType", "json");
		params.put("tmX", tmX);
		params.put("tmY", tmY);
		params.put("ver", "1.1");
		JsonNode items = items("에어코리아 근접측정소", "/MsrstnInfoInqireSvc/getNearbyMsrstnList", params);
		List<JsonNode> sorted = new ArrayList<>();
		items.forEach(sorted::add);
		sorted.sort(Comparator.comparingDouble(item -> {
			Double distance = KmaCodes.number(text(item.path("tm")));
			return distance == null ? Double.MAX_VALUE : distance;
		}));
		return sorted.stream().map(item -> text(item.path("stationName"))).filter(name -> name != null).toList();
	}

	@Override
	public List<SourceData.AirMeasurement> fetchMeasurements(String stationName) {
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("returnType", "json");
		params.put("numOfRows", MEASUREMENT_ROWS);
		params.put("pageNo", 1);
		params.put("stationName", stationName);
		params.put("dataTerm", "DAILY");
		params.put("ver", "1.3");
		JsonNode items = items("에어코리아 실시간 측정", "/ArpltnInforInqireSvc/getMsrstnAcctoRltmMesureDnsty", params);
		List<SourceData.AirMeasurement> result = new ArrayList<>();
		for (JsonNode item : items) {
			Instant measuredAt = measuredAt(text(item.path("dataTime")));
			if (measuredAt == null) {
				continue;
			}
			Double pm10 = KmaCodes.number(text(item.path("pm10Value")));
			Double pm25 = KmaCodes.number(text(item.path("pm25Value")));
			AirGrade pm10Grade = grade(text(item.path("pm10Grade")), AirGrades.pm10(pm10));
			AirGrade pm25Grade = grade(text(item.path("pm25Grade")), AirGrades.pm25(pm25));
			result.add(new SourceData.AirMeasurement(measuredAt, pm10, pm25, pm10Grade, pm25Grade,
					grade(text(item.path("o3Grade")), null)));
		}
		result.sort(Comparator.comparing(SourceData.AirMeasurement::measuredAt).reversed());
		return result;
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

	private JsonNode items(String source, String path, Map<String, Object> params) {
		if (!configured()) {
			throw new SourceException.KeyMissing("에어코리아");
		}
		return calls.call(source, () -> {
			Map<String, Object> withKey = new LinkedHashMap<>(params);
			withKey.put("serviceKey", properties.serviceKey());
			JsonNode root = HttpSourceSupport.parse(source, HttpSourceSupport.get(client, path, withKey));
			JsonNode response = root.path("response");
			String code = text(response.path("header").path("resultCode"));
			if (!"00".equals(code)) {
				throw new SourceException.Failure(source + " 결과 코드 " + code + " "
						+ text(response.path("header").path("resultMsg")));
			}
			JsonNode items = response.path("body").path("items");
			if (!items.isArray() || items.isEmpty()) {
				throw new SourceException.NotReady(source + " 빈 목록");
			}
			return items;
		});
	}

}
