package kr.weathertalk.feeling;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import kr.weathertalk.AuthTestClient;
import kr.weathertalk.member.TemperatureSensitivity;
import kr.weathertalk.neighborhood.ForecastGrid;
import kr.weathertalk.neighborhood.NeighborhoodApi;
import kr.weathertalk.neighborhood.NeighborhoodTestData;
import kr.weathertalk.shared.Ids.NeighborhoodId;
import kr.weathertalk.shared.idempotency.IdempotencyKey;
import kr.weathertalk.weather.WeatherTestBase;

/**
 * 체감 통합 테스트 공통: 날씨 초기화(WeatherTestBase) + 체감 기록 비우기, 회원 가입·체감 기록 요청·기록 행 직접 삽입 도우미.
 * 집계·산출 테스트는 표본을 행으로 직접 넣고(빠르게), 쓰기 규칙 테스트는 실제 API를 거친다.
 */
public abstract class FeelingTestBase extends WeatherTestBase {

	@Autowired
	protected MockMvc mvc;

	@Autowired
	protected NeighborhoodApi neighborhoodApi;

	@Autowired
	protected FeelingApi feelingApi;

	private static boolean weatherWarmedUp;

	@Autowired
	protected kr.weathertalk.feeling.internal.FeelingMappingFlag mappingFlag;

	@BeforeEach
	void resetFeeling() {
		jdbc.sql("TRUNCATE feeling_record, feeling_calibration_offer, feeling_calibration_member, "
				+ "feeling_mapping_version CASCADE").update();
		mappingFlag.set(false);
		// JVM에서 첫 외부 호출(HTTP 클라이언트·JIT 준비)은 테스트용 외부 제한 시간(0.4초)을 넘길 수 있어 한 번 데워 둔다
		if (!weatherWarmedUp) {
			stubs.observation(gridOf(NeighborhoodTestData.N1), clock.instant().truncatedTo(java.time.temporal.ChronoUnit.HOURS),
					20.0);
			weatherApi.findCurrentObservation(NeighborhoodTestData.N1);
			weatherApi.findHourlyForecast(NeighborhoodTestData.N1, null);
			kr.weathertalk.weather.WeatherStubs.resetAll(jdbc, redis, server);
			weatherWarmedUp = true;
		}
	}

	protected ForecastGrid gridOf(NeighborhoodId id) {
		return neighborhoodApi.findNeighborhood(id).orElseThrow().forecastGrid();
	}

	protected AuthTestClient.Session signup(TemperatureSensitivity sensitivity) throws Exception {
		AuthTestClient auth = new AuthTestClient(mvc);
		String providerUserId = AuthTestClient.newProviderUserId();
		String json = auth.completeSignup(auth.signupTicket(providerUserId), auth.agreeAll(), sensitivity.name())
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return AuthTestClient.session(json, providerUserId);
	}

	/** 시계를 접근 토큰 유효 기간(1시간) 너머로 옮긴 테스트용: 같은 계정으로 다시 로그인해 새 토큰을 받는다. */
	protected AuthTestClient.Session relogin(AuthTestClient.Session session) throws Exception {
		String json = new AuthTestClient(mvc).devLogin(session.providerUserId(), null)
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return AuthTestClient.session(json, session.providerUserId());
	}

	protected static String newKey() {
		return UUID.randomUUID().toString();
	}

	/** 앱이 기기 안에서 판정한 현재 동네 ID (좌표 대신, ADR 0009). */
	protected static String locationJson(NeighborhoodId location) {
		return "\"neighborhoodId\":\"" + location.value() + "\"";
	}

	/** 직접 기록 요청. humidity·wind는 null이면 보내지 않는다. */
	protected ResultActions record(AuthTestClient.Session session, String key, String temperature, String humidity,
			String wind, NeighborhoodId location) throws Exception {
		StringBuilder body = new StringBuilder("{");
		if (temperature != null) {
			body.append("\"temperature\":\"").append(temperature).append("\",");
		}
		if (humidity != null) {
			body.append("\"humidity\":\"").append(humidity).append("\",");
		}
		if (wind != null) {
			body.append("\"wind\":\"").append(wind).append("\",");
		}
		body.append(locationJson(location)).append('}');
		return mvc.perform(post("/api/v1/feeling/records").header(HttpHeaders.AUTHORIZATION, session.bearer())
			.header(IdempotencyKey.HEADER, key)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body.toString()));
	}

	protected ResultActions record(AuthTestClient.Session session, String temperature) throws Exception {
		return record(session, newKey(), temperature, null, null, NeighborhoodTestData.INSIDE_N1);
	}

	/** 체감 피드백 요청. shown*은 앱이 보여준 민감도별 체감 값. */
	protected ResultActions feedback(AuthTestClient.Session session, String answer, String shownTag,
			TemperatureSensitivity shownSensitivity, NeighborhoodId shownNeighborhood, Instant basisTime,
			Instant derivedAt, NeighborhoodId location) throws Exception {
		String body = "{\"answer\":\"" + answer + "\",\"shown\":{\"temperatureTag\":\"" + shownTag
				+ "\",\"source\":\"REFERENCE_TABLE\",\"temperatureSensitivity\":\"" + shownSensitivity.name()
				+ "\",\"neighborhoodId\":\"" + shownNeighborhood.value() + "\",\"basisTime\":\"" + basisTime
				+ "\",\"derivedAt\":\"" + derivedAt + "\"}," + locationJson(location) + "}";
		return mvc.perform(post("/api/v1/feeling/feedbacks").header(HttpHeaders.AUTHORIZATION, session.bearer())
			.header(IdempotencyKey.HEADER, newKey())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	protected static String recordId(ResultActions result) throws Exception {
		return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.recordId");
	}

	protected int recordCount() {
		return jdbc.sql("SELECT count(*) FROM feeling_record").query(Integer.class).single();
	}

	/** 표본 행을 직접 넣는다. 생성 시각 = 기록 시각, 피드백 출처면 응답 RIGHT·보여준 단계 = 온도. */
	protected UUID insertRecord(NeighborhoodId neighborhood, TemperatureSensitivity sensitivity, Instant recordedAt,
			int temperature, Integer humidity, Integer wind, FeelingRecordSource source, UUID memberId,
			Double snapshotTemperature) {
		UUID id = UUID.randomUUID();
		boolean feedback = source != FeelingRecordSource.DIRECT;
		jdbc.sql("""
				INSERT INTO feeling_record (id, member_id, neighborhood_code, created_at, recorded_at, temperature_level,
				    humidity_level, wind_level, temperature_sensitivity, source, feedback_answer, feedback_shown_level,
				    feedback_shown_source, snapshot_status, snapshot_observed_at, snapshot_temperature, snapshot_humidity,
				    snapshot_wind_speed)
				VALUES (:id, :member, :nbr, :at, :at, :temp, :hum, :wind, :sens, :source, :answer, :shown, :shownSource,
				    :status, :observedAt, :snapTemp, :snapHum, :snapWind)
				""")
			.param("id", id)
			.param("member", memberId, java.sql.Types.OTHER)
			.param("nbr", neighborhood.value())
			.param("at", Timestamp.from(recordedAt))
			.param("temp", temperature)
			.param("hum", humidity, java.sql.Types.SMALLINT)
			.param("wind", wind, java.sql.Types.SMALLINT)
			.param("sens", sensitivity.name())
			.param("source", source.name())
			.param("answer", feedback ? "RIGHT" : null, java.sql.Types.VARCHAR)
			.param("shown", feedback ? temperature : null, java.sql.Types.SMALLINT)
			.param("shownSource", feedback ? "REFERENCE_TABLE" : null, java.sql.Types.VARCHAR)
			.param("status", snapshotTemperature == null ? "MISSING" : "SECURED")
			.param("observedAt", snapshotTemperature == null ? null : Timestamp.from(recordedAt), java.sql.Types.TIMESTAMP)
			.param("snapTemp", snapshotTemperature, java.sql.Types.DOUBLE)
			.param("snapHum", snapshotTemperature == null ? null : 60.0, java.sql.Types.DOUBLE)
			.param("snapWind", snapshotTemperature == null ? null : 2.0, java.sql.Types.DOUBLE)
			.update();
		return id;
	}

	@org.junit.jupiter.api.AfterEach
	void resetMappingFlag() {
		mappingFlag.set(false); // 기능 플래그 기본값(feeling.mapping.enabled=false)
	}

	protected UUID insertRecord(NeighborhoodId neighborhood, TemperatureSensitivity sensitivity, Instant recordedAt,
			int temperature) {
		return insertRecord(neighborhood, sensitivity, recordedAt, temperature, null, null, FeelingRecordSource.DIRECT,
				null, null);
	}

}
