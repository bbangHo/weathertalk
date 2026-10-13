package kr.weathertalk.notification.internal;

import static kr.weathertalk.neighborhood.NeighborhoodTestData.INSIDE_N1;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N1;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N7;
import static kr.weathertalk.weather.WeatherStubs.kst;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import kr.weathertalk.AuthTestClient;
import kr.weathertalk.IntegrationTest;
import kr.weathertalk.member.TemperatureSensitivity;
import kr.weathertalk.neighborhood.ForecastGrid;
import kr.weathertalk.notification.NotificationTestBase;
import kr.weathertalk.notification.internal.push.PushMessage;
import kr.weathertalk.shared.time.TimeConfig;

/**
 * 정기 날씨 알림 (FR-NOTI-09, ADR 0013). 06:30 회차(심야 금지 시간 22:00~07:00 안)로 정기 알림 예외를 함께 검증한다.
 * 현재 06:00 실황 12℃(보통·추위를 많이 탐 = 2단계 '추움'), 05:00 단기예보에서 09시 비.
 */
@IntegrationTest
class DailyWeatherAlertTests extends NotificationTestBase {

	@Autowired
	DailyWeatherAlerts daily;

	private void stubN1Weather() {
		ForecastGrid grid = gridOf(N1);
		stubs.observation(grid, kst("2026-10-13T06:00"), 12.0);
		stubs.shortTerm(grid, kst("2026-10-13T05:00"), 24, at -> at.getHour() == 9
				? Map.of("PTY", "1", "POP", "70", "PCP", "2.0mm", "TMP", "13") : Map.of("TMP", "15"));
	}

	private String name() {
		return neighborhoodApi.findNeighborhood(N1).orElseThrow().eupmyeondongName();
	}

	/**
	 * 심야 금지 시간 중 시각(06:30)도 보낸다(ADR 0013). (동네, 민감도)마다 문구를 만들고, 비회원은 알림용 민감도(기본 보통), 회원 연결 기기는 회원
	 * 프로필 민감도와 회원의 현재 동네로 발송 시점에 해석한다(BR-NOTI-06). 문구에 내부 코드가 없다(legacy 05-#8), 탭하면 홈(해당 동네).
	 */
	@Test
	void dailyRoundSendsDuringQuietHoursWithPerSensitivityContent_05_8() throws Exception {
		clock.set(kst("2026-10-13T06:30").plusSeconds(20));
		stubN1Weather();
		DeviceHandle guest = guestDevice();
		subscribeFixed(guest, N1, List.of("06:30"), false, false);
		AuthTestClient.Session cold = signup(TemperatureSensitivity.COLD_SENSITIVE);
		neighborhoodApi.confirmCurrentNeighborhood(cold.memberId(), INSIDE_N1).orElseThrow();
		DeviceHandle member = registerDevice("daily-member-" + UUID.randomUUID(), cold);
		subscribeFollow(member, List.of("06:30", "18:00"), false, false);
		DeviceHandle otherTime = guestDevice();
		subscribeFixed(otherTime, N1, List.of("07:00"), false, false);

		Dispatcher.Report report = daily.runRound(kst("2026-10-13T06:30").atZone(TimeConfig.SEOUL)).orElseThrow();
		assertThat(report.sent()).isEqualTo(2);

		PushMessage guestMessage = pushes.sentTo(guest.token()).getFirst();
		assertThat(guestMessage.title()).isEqualTo(name() + " 오늘 날씨");
		assertThat(guestMessage.body()).startsWith("12°")
			.contains("최저 ")
			.contains("보통 체질인 분에겐 '추움'으로 느껴질 거예요")
			.contains("👕 두꺼운 겉옷이 필요해요.")
			.contains("오전 9시쯤 비 소식이 있어요")
			.doesNotContain("null")
			.doesNotContainPattern("[A-Z]{2,}");
		assertThat(guestMessage.data()).containsEntry("target", "home").containsEntry("neighborhoodId", N1.value())
			.containsEntry("kind", "DAILY_WEATHER");
		assertThat(pushes.sentTo(member.token())).singleElement()
			.satisfies(m -> assertThat(m.body()).contains("추위를 많이 타는 분에겐 '추움'으로 느껴질 거예요"));
		assertThat(pushes.sentTo(otherTime.token())).isEmpty();

		// 같은 회차는 한 번만 열린다(다른 인스턴스·재실행도 같다)
		assertThat(daily.runRound(kst("2026-10-13T06:30").atZone(TimeConfig.SEOUL))).isEmpty();
		assertThat(pushes.sent()).hasSize(2);
	}

	/**
	 * legacy 05-#5·04-#24: 한 동네의 날씨가 없거나 동네를 모르는 기기가 있어도 다른 동네·회차 전체를 막지 않는다. 각각 '생략(날씨 없음)',
	 * '생략(동네 미확인)'으로 남는다. 부가 값(어제 비교·체감)이 없으면 그 줄만 빠진다.
	 */
	@Test
	void missingWeatherOrNeighborhoodIsIsolatedPerTarget_05_5() throws Exception {
		clock.set(kst("2026-10-13T06:30").plusSeconds(20));
		stubN1Weather();
		DeviceHandle ok = guestDevice();
		subscribeFixed(ok, N1, List.of("06:30"), false, false);
		DeviceHandle noWeather = guestDevice();
		subscribeFixed(noWeather, N7, List.of("06:30"), false, false);
		DeviceHandle unknown = guestDevice();
		subscribeFollow(unknown, List.of("06:30"), false, false);

		Dispatcher.Report report = daily.runRound(kst("2026-10-13T06:30").atZone(TimeConfig.SEOUL)).orElseThrow();
		assertThat(report.sent()).isEqualTo(1);
		assertThat(pushes.sentTo(ok.token())).singleElement()
			.satisfies(m -> assertThat(m.body()).doesNotContain("어제"));
		assertThat(delivery("DAILY_WEATHER", noWeather.id())).containsEntry("skip_reason", "NO_WEATHER");
		assertThat(delivery("DAILY_WEATHER", unknown.id())).containsEntry("skip_reason", "NEIGHBORHOOD_UNKNOWN")
			.containsEntry("target_key", "-");
	}

	/** FR-NOTI-09 실패: 회차가 max-delay(30분)를 넘겨 열리면 보내지 않고 '생략(지연 초과)'. 정각 기준 10분 단위 회차를 tick이 연다. */
	@Test
	void lateRoundIsSkippedAndTickOpensDueRounds() throws Exception {
		DeviceHandle device = guestDevice();
		subscribeFixed(device, N1, List.of("06:00", "06:30"), false, false);
		stubN1Weather();

		clock.set(kst("2026-10-13T06:31"));
		daily.tick();
		assertThat(pushes.sentTo(device.token())).hasSize(1);
		Map<String, Object> skipped = deliveries("DAILY_WEATHER").stream()
			.filter(r -> "2026-10-13T06:00".equals(r.get("round_key")))
			.findFirst()
			.orElseThrow();
		assertThat(skipped).containsEntry("skip_reason", "DELAY_EXCEEDED");
		assertThat(deliveries("DAILY_WEATHER").stream().filter(r -> "2026-10-13T06:30".equals(r.get("round_key"))))
			.singleElement()
			.satisfies(r -> assertThat(r).containsEntry("status", "SUCCEEDED"));

		// 다시 돌아도 이미 열린 회차는 그대로
		daily.tick();
		assertThat(pushes.sentTo(device.token())).hasSize(1);
	}

}
