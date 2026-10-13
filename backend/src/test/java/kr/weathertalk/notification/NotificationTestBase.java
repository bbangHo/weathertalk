package kr.weathertalk.notification;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import kr.weathertalk.AuthTestClient;
import kr.weathertalk.feeling.FeelingTestBase;
import kr.weathertalk.member.TemperatureSensitivity;
import kr.weathertalk.shared.Ids.NeighborhoodId;

/**
 * 알림 통합 테스트 공통: 날씨·체감 초기화(FeelingTestBase) + 알림 테이블·알림 구독 동네 수·가짜 푸시 발송기 초기화, 기기·구독 도우미.
 * 끝나면 알림 데이터를 지워 다른 테스트의 활성 동네 판정에 알림 구독 동네가 섞이지 않게 한다.
 */
public abstract class NotificationTestBase extends FeelingTestBase {

	protected record DeviceHandle(UUID id, String key, String token) {

		public MockHttpServletRequestBuilder sign(MockHttpServletRequestBuilder request) {
			return request.header("X-Device-Id", id.toString()).header("X-Device-Key", key);
		}

	}

	@Autowired
	protected RecordingPushSender pushes;

	@BeforeEach
	void resetNotification() {
		cleanNotification();
		pushes.clear();
	}

	@AfterEach
	void removeNotificationData() {
		cleanNotification();
		pushes.clear();
	}

	protected void cleanNotification() {
		jdbc.sql("DELETE FROM noti_device").update();
		jdbc.sql("DELETE FROM noti_daily_round").update();
		jdbc.sql("DELETE FROM nbr_notification_subscription").update();
	}

	protected AuthTestClient.Session member() throws Exception {
		return signup(TemperatureSensitivity.NORMAL);
	}

	protected ResultActions confirm(UUID deviceId, String deviceKey, String token, AuthTestClient.Session session)
			throws Exception {
		String body = "{" + (deviceId == null ? "" : "\"deviceId\":\"" + deviceId + "\",\"deviceKey\":\"" + deviceKey + "\",")
				+ "\"platform\":\"ANDROID\",\"pushToken\":" + (token == null ? "null" : "\"" + token + "\"")
				+ ",\"osPermission\":\"GRANTED\"}";
		MockHttpServletRequestBuilder request = post("/api/v1/notification/devices/confirm")
			.contentType(MediaType.APPLICATION_JSON)
			.content(body);
		if (session != null) {
			request.header(HttpHeaders.AUTHORIZATION, session.bearer());
		}
		return mvc.perform(request);
	}

	protected DeviceHandle registerDevice(String token, AuthTestClient.Session session) throws Exception {
		String json = confirm(null, null, token, session).andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return new DeviceHandle(UUID.fromString(JsonPath.read(json, "$.deviceId")), JsonPath.read(json, "$.deviceKey"),
				token);
	}

	protected DeviceHandle guestDevice() throws Exception {
		return registerDevice("token-" + UUID.randomUUID(), null);
	}

	protected ResultActions confirm(DeviceHandle device, AuthTestClient.Session session) throws Exception {
		return confirm(device.id(), device.key(), device.token(), session);
	}

	protected ResultActions subscribe(DeviceHandle device, String targetType, NeighborhoodId neighborhood,
			boolean daily, List<String> dailyTimes, boolean precipitation, boolean fieldReport) throws Exception {
		String times = dailyTimes == null ? "null"
				: dailyTimes.stream().map(t -> "\"" + t + "\"").collect(Collectors.joining(",", "[", "]"));
		String body = "{\"targetType\":\"" + targetType + "\",\"neighborhoodId\":"
				+ (neighborhood == null ? "null" : "\"" + neighborhood.value() + "\"") + ",\"dailyEnabled\":" + daily
				+ ",\"dailyTimes\":" + times + ",\"precipitationForecastEnabled\":" + precipitation
				+ ",\"fieldReportPrecipitationEnabled\":" + fieldReport + "}";
		return mvc.perform(device.sign(put("/api/v1/notification/subscriptions"))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	protected void subscribeFixed(DeviceHandle device, NeighborhoodId neighborhood, List<String> dailyTimes,
			boolean precipitation, boolean fieldReport) throws Exception {
		subscribe(device, "FIXED", neighborhood, dailyTimes != null && !dailyTimes.isEmpty(),
				dailyTimes == null ? List.of() : dailyTimes, precipitation, fieldReport)
			.andExpect(status().isOk());
	}

	protected void subscribeFollow(DeviceHandle device, List<String> dailyTimes, boolean precipitation,
			boolean fieldReport) throws Exception {
		subscribe(device, "FOLLOW_CURRENT", null, dailyTimes != null && !dailyTimes.isEmpty(),
				dailyTimes == null ? List.of() : dailyTimes, precipitation, fieldReport)
			.andExpect(status().isOk());
	}

	protected void reportNeighborhood(DeviceHandle device, NeighborhoodId neighborhood) throws Exception {
		mvc.perform(device.sign(put("/api/v1/notification/devices/me/neighborhood"))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"neighborhoodId\":\"" + neighborhood.value() + "\"}"))
			.andExpect(status().isNoContent());
	}

	protected int subscriptionCount(NeighborhoodId neighborhood) {
		return jdbc.sql("SELECT coalesce(max(subscription_count), 0) FROM nbr_notification_subscription WHERE neighborhood_code = :c")
			.param("c", neighborhood.value())
			.query(Integer.class)
			.single();
	}

	/** 발송 기록 (종류별). 각 행: device_id, target_key, status, skip_reason, attempts. */
	protected List<Map<String, Object>> deliveries(String kind) {
		return jdbc.sql("SELECT device_id, round_key, target_key, status, skip_reason, attempts, payload FROM noti_delivery "
				+ "WHERE kind = :k ORDER BY created_at")
			.param("k", kind)
			.query()
			.listOfRows();
	}

	protected Map<String, Object> delivery(String kind, UUID deviceId) {
		return deliveries(kind).stream()
			.filter(r -> deviceId.equals(r.get("device_id")))
			.findFirst()
			.orElseThrow(() -> new AssertionError("발송 기록 없음: " + kind + " " + deviceId));
	}

}
