package kr.weathertalk.notification;

import static kr.weathertalk.neighborhood.NeighborhoodTestData.N1;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N2;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N3;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N4;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N5;
import static kr.weathertalk.weather.WeatherStubs.kst;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.modulith.test.Scenario;

import kr.weathertalk.AuthTestClient;
import kr.weathertalk.IntegrationTest;
import kr.weathertalk.notification.internal.NotificationMaintenance;

/**
 * 기기 확인·알림 구독·외출 시각 (FR-NOTI-01~08, 13, 14, 17, 18)과 알림 구독 동네 반영(FR-NBR-10).
 */
@IntegrationTest
class DeviceAndSubscriptionTests extends NotificationTestBase {

	@Autowired
	NotificationMaintenance maintenance;

	/** FR-NOTI-01·02: 첫 호출은 기기 ID·기기 키 발급(키는 이 응답에서만). 이후 호출은 멱등, 키가 틀리면 거절, 없는 기기는 다시 등록 안내. */
	@Test
	void confirmRegistersOnceAndVerifiesDeviceKey() throws Exception {
		DeviceHandle device = guestDevice();
		assertThat(device.key()).isNotBlank();
		assertThat(jdbc.sql("SELECT key_hash FROM noti_device WHERE id = :id").param("id", device.id())
			.query(String.class)
			.single()).isNotEqualTo(device.key()).hasSize(64);

		confirm(device, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.registered").value(false))
			.andExpect(jsonPath("$.deviceKey").doesNotExist())
			.andExpect(jsonPath("$.linkedToMember").value(false));
		confirm(device.id(), "wrong-key", device.token(), null).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("DEVICE_KEY_MISMATCH"));
		confirm(UUID.randomUUID(), device.key(), device.token(), null).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.reason").value("DEVICE_NOT_FOUND"));
	}

	/** BR-NOTI-03: 같은 푸시 토큰은 기기 하나에만. 재설치(새 기기 등록)로 토큰이 겹치면 이전 기기를 지운다. */
	@Test
	void samePushTokenMovesToTheNewDevice() throws Exception {
		DeviceHandle old = registerDevice("shared-token", null);
		subscribeFixed(old, N3, List.of("07:00"), true, true);
		assertThat(subscriptionCount(N3)).isEqualTo(1);

		DeviceHandle reinstalled = registerDevice("shared-token", null);
		assertThat(jdbc.sql("SELECT count(*) FROM noti_device WHERE id = :id").param("id", old.id())
			.query(Integer.class)
			.single()).isZero();
		// 지운 기기의 구독 동네도 반영에서 빠진다(FR-NBR-10)
		assertThat(subscriptionCount(N3)).isZero();
		confirm(reinstalled, null).andExpect(status().isOk());
	}

	/**
	 * BR-NOTI-04·05, Q15: 로그인 직후 기기 확인 → 회원 연결 + 댓글 알림 켬. 로그아웃 직후(토큰 없이) 기기 확인 → 연결 해제 + 댓글 알림만 끔,
	 * 동네별 구독·외출 시각은 비회원 구독으로 유지. 다시 로그인하면 댓글 알림이 다시 켜진다.
	 */
	@Test
	void loginLogoutReconciliationKeepsSubscriptionsAndTogglesOnlyCommentAlert() throws Exception {
		DeviceHandle device = guestDevice();
		subscribeFixed(device, N2, List.of("07:00"), true, false);
		mvc.perform(device.sign(put("/api/v1/notification/devices/me/departure-times"))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"departureTimes\":[\"08:00\"]}")).andExpect(status().isOk());
		AuthTestClient.Session session = member();

		confirm(device, session).andExpect(jsonPath("$.linkedToMember").value(true))
			.andExpect(jsonPath("$.commentAlertEnabled").value(true));
		mvc.perform(device.sign(put("/api/v1/notification/devices/me/comment-alert"))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"enabled\":false}")).andExpect(jsonPath("$.enabled").value(false));
		// 같은 회원으로 다시 확인해도 사용자가 끈 댓글 알림은 그대로
		confirm(device, session).andExpect(jsonPath("$.commentAlertEnabled").value(false));

		confirm(device, null).andExpect(jsonPath("$.linkedToMember").value(false))
			.andExpect(jsonPath("$.commentAlertEnabled").value(false));
		mvc.perform(device.sign(get("/api/v1/notification/subscriptions")))
			.andExpect(jsonPath("$.subscriptions.length()").value(1))
			.andExpect(jsonPath("$.subscriptions[0].neighborhoodId").value(N2.value()))
			.andExpect(jsonPath("$.guestSensitivity").value("NORMAL"));
		mvc.perform(device.sign(get("/api/v1/notification/devices/me/departure-times")))
			.andExpect(jsonPath("$.departures[0].time").value("08:00:00"));
		// 비회원 기기는 댓글 알림을 켤 수 없다(회원 전용)
		mvc.perform(device.sign(put("/api/v1/notification/devices/me/comment-alert"))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"enabled\":true}")).andExpect(jsonPath("$.code").value("SIGNUP_REQUIRED"));

		// 재로그인 → 댓글 알림 다시 켬(Q15 해소)
		confirm(device, session).andExpect(jsonPath("$.linkedToMember").value(true))
			.andExpect(jsonPath("$.commentAlertEnabled").value(true));
	}

	/** FR-NOTI-14: 탈퇴 → 그 회원의 기기 연결 해제, 댓글 알림만 끔, 동네별 구독·외출 시각 유지. 회원 ID를 기기에 남기지 않는다. */
	@Test
	void withdrawalUnlinksDevicesButKeepsSubscriptions(Scenario scenario) throws Exception {
		AuthTestClient.Session session = member();
		DeviceHandle device = registerDevice("withdraw-" + UUID.randomUUID(), session);
		subscribeFollow(device, List.of("07:00"), true, true);

		scenario.stimulate(() -> {
			try {
				mvc.perform(delete("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION, session.bearer()))
					.andExpect(status().isNoContent());
			}
			catch (Exception e) {
				throw new IllegalStateException(e);
			}
		})
			.andWaitAtMost(Duration.ofSeconds(10))
			.andWaitForStateChange(() -> jdbc.sql("SELECT count(*) FROM noti_device WHERE member_id = :m")
				.param("m", session.memberId().value())
				.query(Integer.class)
				.single(), count -> count == 0)
			.andVerify(count -> {
				assertThat(jdbc.sql("SELECT comment_enabled FROM noti_device WHERE id = :id").param("id", device.id())
					.query(Boolean.class)
					.single()).isFalse();
				assertThat(jdbc.sql("SELECT count(*) FROM noti_subscription WHERE device_id = :id")
					.param("id", device.id())
					.query(Integer.class)
					.single()).isEqualTo(1);
			});
	}

	/**
	 * legacy 05-#13, 07-#13: 서버가 시각 단위·개수·구독 수·지정 동네를 검증한다. 세 종류를 모두 끄면 구독이 지워지고(본문 없는 알림 경로 없음),
	 * 정기 날씨 알림 시각을 0개로 두면 꺼진 것으로 저장된다(모두 끄기 가능). 강수 알림은 정기 알림과 독립적으로 켤 수 있다.
	 */
	@Test
	void subscriptionValidationAndAllOffDeletes_05_13_07_13() throws Exception {
		DeviceHandle device = guestDevice();
		subscribe(device, "FIXED", N1, true, List.of("07:05"), false, false).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		subscribe(device, "FIXED", N1, true, List.of("07:00", "12:00", "18:00", "21:00"), false, false)
			.andExpect(status().isBadRequest());
		subscribe(device, "FIXED", new kr.weathertalk.shared.Ids.NeighborhoodId("0000000000"), true, List.of("07:00"),
				false, false)
			.andExpect(status().isNotFound());

		subscribe(device, "FIXED", N1, true, List.of(), true, false).andExpect(status().isOk())
			.andExpect(jsonPath("$.dailyEnabled").value(false))
			.andExpect(jsonPath("$.precipitationForecastEnabled").value(true));
		subscribe(device, "FIXED", N1, false, List.of(), false, false).andExpect(status().isNoContent());
		mvc.perform(device.sign(get("/api/v1/notification/subscriptions")))
			.andExpect(jsonPath("$.subscriptions.length()").value(0));

		subscribe(device, "FIXED", N1, true, null, false, false).andExpect(status().isOk())
			.andExpect(jsonPath("$.dailyTimes[0]").value("07:00:00"));
		subscribe(device, "FIXED", N2, false, List.of(), false, true).andExpect(status().isOk());
		subscribe(device, "FOLLOW_CURRENT", null, false, List.of(), true, false).andExpect(status().isOk());
		subscribe(device, "FIXED", N3, false, List.of(), true, false).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.violations[0]").value("SUBSCRIPTION_LIMIT"));
		// 같은 구독 동네는 생성이 아니라 변경
		subscribe(device, "FIXED", N2, false, List.of(), true, true).andExpect(status().isOk());
		assertThat(jdbc.sql("SELECT count(*) FROM noti_subscription WHERE device_id = :id").param("id", device.id())
			.query(Integer.class)
			.single()).isEqualTo(3);
	}

	/**
	 * FR-NBR-10 (contracts §2.2 활성 동네): 비회원 기기만 구독한 동네도 활성 동네가 된다. 지정 동네는 그 동네, 비회원의 현재 동네 따라가기는
	 * 기기 현재 동네가 반영 대상이고, 회원 연결 기기의 현재 동네 따라가기는 세지 않는다. 구독이 사라지면 빠진다.
	 */
	@Test
	void subscribedNeighborhoodsBecomeActiveNeighborhoods() throws Exception {
		// 다른 테스트 회원의 최근 활동(현재 동네 확정)이 섞이지 않도록 먼 미래에서 본다
		clock.set(kr.weathertalk.MutableClock.BASE.plus(Duration.ofDays(2000)));
		DeviceHandle guest = guestDevice();
		subscribeFollow(guest, null, true, false);
		assertThat(neighborhoodApi.findActiveNeighborhoods()).doesNotContain(N4);
		reportNeighborhood(guest, N4);
		assertThat(subscriptionCount(N4)).isEqualTo(1);
		assertThat(neighborhoodApi.findActiveNeighborhoods()).contains(N4);

		// 기기 현재 동네가 바뀌면 이전 동네 −1, 새 동네 +1
		reportNeighborhood(guest, N5);
		assertThat(subscriptionCount(N4)).isZero();
		assertThat(neighborhoodApi.findActiveNeighborhoods()).contains(N5).doesNotContain(N4);

		subscribeFixed(guest, N3, null, false, true);
		assertThat(neighborhoodApi.findActiveNeighborhoods()).contains(N3, N5);

		// 회원과 연결되면 현재 동네 따라가기는 회원 활동 쪽 사유가 되므로 세지 않는다(지정 동네는 그대로)
		AuthTestClient.Session session = member();
		confirm(guest, session).andExpect(status().isOk());
		assertThat(subscriptionCount(N5)).isZero();
		assertThat(subscriptionCount(N3)).isEqualTo(1);

		mvc.perform(guest.sign(delete("/api/v1/notification/subscriptions")).param("targetType", "FIXED")
			.param("neighborhoodId", N3.value())).andExpect(status().isNoContent());
		assertThat(subscriptionCount(N3)).isZero();
		assertThat(neighborhoodApi.findActiveNeighborhoods()).doesNotContain(N3);
	}

	/** 02 §9 미결 8: 증감이 어긋나도 대조 배치가 notification 기준으로 바로잡는다. */
	@Test
	void reconciliationRestoresCountsFromNotification() throws Exception {
		DeviceHandle device = guestDevice();
		subscribeFixed(device, N2, null, true, false);
		jdbc.sql("UPDATE nbr_notification_subscription SET subscription_count = 7 WHERE neighborhood_code = :c")
			.param("c", N2.value())
			.update();
		jdbc.sql("INSERT INTO nbr_notification_subscription VALUES (:c, 4, now())").param("c", N3.value()).update();

		assertThat(maintenance.reconcileSubscribedNeighborhoods()).isEqualTo(2);
		assertThat(subscriptionCount(N2)).isEqualTo(1);
		assertThat(subscriptionCount(N3)).isZero();
	}

	/**
	 * FR-NOTI-17·18 + contracts §2.7: 외출 시각은 기기 단위(비회원 포함), 서버 검증, 다음 외출 시각은 notification이 고른다 —
	 * 오늘 남은 것 중 가장 가까운 시각(그 시(時)가 지나지 않았으면 오늘), 없으면 내일 첫 시각.
	 */
	@Test
	void departureTimesAndNextDeparture() throws Exception {
		clock.set(kst("2026-10-13T08:40"));
		DeviceHandle device = guestDevice();
		mvc.perform(device.sign(put("/api/v1/notification/devices/me/departure-times"))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"departureTimes\":[\"18:00\",\"07:30\",\"08:30\"]}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.departures[0].time").value("07:30:00"))
			.andExpect(jsonPath("$.departures[0].nextAt").value("2026-10-13T22:30:00Z"))
			.andExpect(jsonPath("$.departures[1].nextAt").value("2026-10-12T23:30:00Z"))
			.andExpect(jsonPath("$.nextDepartureAt").value("2026-10-12T23:30:00Z"));

		clock.set(kst("2026-10-13T19:10"));
		mvc.perform(device.sign(get("/api/v1/notification/devices/me/departure-times")))
			.andExpect(jsonPath("$.nextDepartureAt").value("2026-10-13T22:30:00Z"));

		mvc.perform(device.sign(put("/api/v1/notification/devices/me/departure-times"))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"departureTimes\":[\"07:15\",\"07:15\"]}"))
			.andExpect(status().isBadRequest());
		mvc.perform(put("/api/v1/notification/devices/me/departure-times").header("X-Device-Id", device.id().toString())
			.header("X-Device-Key", "not-mine")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"departureTimes\":[]}")).andExpect(status().isForbidden());
		// 회원 토큰만으로는 기기 설정을 바꿀 수 없다(BR-NOTI-15)
		AuthTestClient.Session session = member();
		mvc.perform(put("/api/v1/notification/devices/me/departure-times").header(HttpHeaders.AUTHORIZATION,
				session.bearer()).contentType(MediaType.APPLICATION_JSON).content("{\"departureTimes\":[]}"))
			.andExpect(status().is4xxClientError());
	}

	/** FR-NOTI-13: 마지막 확인이 stale-after(60일)보다 오래되면 발송 중지, 그 뒤 purge-after(30일)가 지나면 기기·구독 삭제. */
	@Test
	void staleDevicesAreSuspendedThenPurged() throws Exception {
		DeviceHandle device = guestDevice();
		subscribeFixed(device, N2, null, true, false);
		clock.advance(Duration.ofDays(61));
		assertThat(maintenance.cleanUpStaleDevices()[0]).isEqualTo(1);
		assertThat(jdbc.sql("SELECT status FROM noti_device WHERE id = :id").param("id", device.id())
			.query(String.class)
			.single()).isEqualTo("SUSPENDED");
		clock.advance(Duration.ofDays(31));
		maintenance.cleanUpStaleDevices();
		assertThat(jdbc.sql("SELECT count(*) FROM noti_device WHERE id = :id").param("id", device.id())
			.query(Integer.class)
			.single()).isZero();
		assertThat(subscriptionCount(N2)).isZero();
	}

	/** 장기 미사용으로 멈춘 기기도 다시 기기 확인을 하면 활성으로 돌아온다. */
	@Test
	void staleDeviceComesBackOnConfirm() throws Exception {
		DeviceHandle device = guestDevice();
		clock.advance(Duration.ofDays(61));
		maintenance.cleanUpStaleDevices();
		confirm(device, null).andExpect(jsonPath("$.status").value("ACTIVE"));
		subscribe(device, "FIXED", N1, false, List.of(), true, false).andExpect(status().isOk());
		subscribe(device, "FIXED", N1, false, List.of(), true, false).andExpect(jsonPath("$.consentedAt").exists());
	}

}
