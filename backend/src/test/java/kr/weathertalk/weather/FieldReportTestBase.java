package kr.weathertalk.weather;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import kr.weathertalk.AuthTestClient;
import kr.weathertalk.IntegrationTestConfiguration.ThreadSafeEventRecorder;
import kr.weathertalk.neighborhood.DeviceLocation;
import kr.weathertalk.neighborhood.NeighborhoodApi;
import kr.weathertalk.neighborhood.NeighborhoodTestData;
import kr.weathertalk.shared.Ids.MemberId;
import kr.weathertalk.shared.idempotency.IdempotencyKey;
import kr.weathertalk.weather.internal.fieldreport.FieldReportService;

/**
 * 현장 제보 통합 테스트 공통: 날씨 초기화(WeatherTestBase, 현장 제보·발행 기록 테이블 포함) + 이벤트 기록 비우기 + 제보 도우미.
 * 규칙 테스트는 서비스를 직접 부르고(무작위 회원 ID — 현재 동네 확정은 회원 존재를 따지지 않는다), 인증·약관·멱등은 웹 API로 검증한다.
 * 테스트가 만든 현재 동네 기록은 끝나면 지워 다른 테스트의 활성 동네 판정에 섞이지 않게 한다.
 */
public abstract class FieldReportTestBase extends WeatherTestBase {

	/** 동네 7(예보 격자 98·76) 대표점 옆 */
	public static final DeviceLocation AT_N7 = NeighborhoodTestData.location(35.105, 129.005, 20.0);

	@Autowired
	protected MockMvc mvc;

	@Autowired
	protected FieldReportService fieldReports;

	@Autowired
	protected NeighborhoodApi neighborhoodApi;

	@Autowired
	protected ThreadSafeEventRecorder recorder;

	private final List<MemberId> createdMembers = new ArrayList<>();

	@BeforeEach
	void resetFieldReportEvents() {
		recorder.clear();
	}

	@AfterEach
	void removeCurrentNeighborhoods() {
		for (MemberId member : createdMembers) {
			jdbc.sql("DELETE FROM nbr_current_neighborhood WHERE member_id = :id").param("id", member.value()).update();
		}
		createdMembers.clear();
	}

	protected MemberId newMember() {
		MemberId member = MemberId.random();
		createdMembers.add(member);
		return member;
	}

	protected List<MemberId> newMembers(int count) {
		List<MemberId> members = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			members.add(newMember());
		}
		return members;
	}

	protected FieldReportResult report(MemberId member, FieldReportKind kind) {
		return fieldReports.submit(member, kind, NeighborhoodTestData.INSIDE_N1);
	}

	protected FieldReportResult reportAtN7(MemberId member, FieldReportKind kind) {
		return fieldReports.submit(member, kind, AT_N7);
	}

	protected ResultActions submitViaApi(AuthTestClient.Session session, String key, String kind,
			DeviceLocation location) throws Exception {
		createdMembers.add(session.memberId());
		String body = "{\"kind\":" + (kind == null ? "null" : "\"" + kind + "\"") + ",\"latitude\":"
				+ location.latitude() + ",\"longitude\":" + location.longitude() + ",\"accuracyMeters\":"
				+ location.accuracyMeters() + "}";
		return mvc.perform(post("/api/v1/weather/field-reports").header(HttpHeaders.AUTHORIZATION, session.bearer())
			.header(IdempotencyKey.HEADER, key)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	protected static String newKey() {
		return UUID.randomUUID().toString();
	}

	protected int reportRows() {
		return jdbc.sql("SELECT count(*) FROM weather_field_report").query(Integer.class).single();
	}

	protected <T> List<T> events(Class<T> type) {
		return recorder.of(type);
	}

	/** 구독자가 없는 이벤트는 이벤트 발행 저장소에 쌓이지 않는다(M7에서 구독자를 붙이면 그때부터 영속화된다). */
	protected int storedPublications(Class<?> type) {
		return jdbc.sql("SELECT count(*) FROM event_publication WHERE event_type = :type")
			.param("type", type.getName())
			.query(Integer.class)
			.single();
	}

}
