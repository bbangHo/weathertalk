package kr.weathertalk.notification.internal;

import static kr.weathertalk.neighborhood.NeighborhoodTestData.INSIDE_N1;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N1;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N2;
import static kr.weathertalk.weather.WeatherStubs.kst;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import kr.weathertalk.AuthTestClient;
import kr.weathertalk.IntegrationTest;
import kr.weathertalk.community.CommentCreated;
import kr.weathertalk.notification.NotificationTestBase;
import kr.weathertalk.notification.internal.push.PushMessage;
import kr.weathertalk.notification.internal.push.PushResult;
import kr.weathertalk.shared.Ids.CommentId;
import kr.weathertalk.shared.Ids.MemberId;
import kr.weathertalk.shared.Ids.PostId;
import kr.weathertalk.weather.FieldReportPrecipitationReached;
import kr.weathertalk.weather.PrecipitationStartForecasted;
import kr.weathertalk.weather.PrecipitationType;

/**
 * 이벤트 알림 3종 (FR-NOTI-10·11·19)과 공통 발송 정책: 수신자 규칙, 행위자 본인 제외, 쿨다운(원자 선점·성공 시 확정·실패 시 해제),
 * 심야 정책(강수 예보 보류·현장 제보·댓글 버림), 이벤트 재전달 멱등, 무효 토큰 즉시 정리, 일시 오류 재시도, 건 단위 실패 격리.
 * 구독자를 직접 불러(이벤트 발행 저장소 경유 없이) 결정적으로 검증한다. 발행 → 구독 경로는 NotificationScenarioTests가 검증한다.
 */
@IntegrationTest
class EventAlertTests extends NotificationTestBase {

	static final Instant NOON = kst("2026-10-13T12:00");

	@Autowired
	EventAlerts alerts;

	@Autowired
	Dispatcher dispatcher;

	private String name(kr.weathertalk.shared.Ids.NeighborhoodId id) {
		return neighborhoodApi.findNeighborhood(id).orElseThrow().eupmyeondongName();
	}

	private CommentCreated comment(AuthTestClient.Session commenter, PostId post, AuthTestClient.Session postAuthor,
			AuthTestClient.Session parentAuthor) {
		return new CommentCreated(UUID.randomUUID(), new CommentId(UUID.randomUUID()), commenter.memberId(), post,
				postAuthor.memberId(), parentAuthor == null ? null : new CommentId(UUID.randomUUID()),
				parentAuthor == null ? null : parentAuthor.memberId(), clock.instant());
	}

	private static PostId newPost() {
		return new PostId(UUID.randomUUID());
	}

	private FieldReportPrecipitationReached fieldReport(PrecipitationType type, int count, MemberId... reporters) {
		return new FieldReportPrecipitationReached(UUID.randomUUID(), N1, type, clock.instant(), count,
				List.of(reporters));
	}

	private PrecipitationStartForecasted forecast(Instant expectedStart) {
		return new PrecipitationStartForecasted(UUID.randomUUID(), N1, expectedStart, PrecipitationType.RAIN, "약한 비",
				clock.instant().minusSeconds(900), clock.instant());
	}

	/**
	 * FR-NOTI-19, BR-NOTI-09: 수신 = {게시글 작성자} ∪ {상위 댓글 작성자} − {댓글 작성자}. 게시글 작성자이면서 상위 댓글 작성자면 게시글 작성자 문구로 1회.
	 * 회원에 연결된 기기에만 가고 문구에 본문·닉네임이 없으며 탭하면 게시글 상세로 간다.
	 */
	@Test
	void commentAlertRecipients() throws Exception {
		clock.set(NOON);
		AuthTestClient.Session author = member();
		AuthTestClient.Session neighbor = member();
		AuthTestClient.Session third = member();
		DeviceHandle authorDevice = registerDevice("author-" + UUID.randomUUID(), author);
		DeviceHandle neighborDevice = registerDevice("neighbor-" + UUID.randomUUID(), neighbor);
		DeviceHandle thirdDevice = registerDevice("third-" + UUID.randomUUID(), third);
		guestDevice();

		// 이웃이 내 글에 댓글 → 게시글 작성자만
		PostId post1 = newPost();
		alerts.comment(comment(neighbor, post1, author, null));
		assertThat(pushes.sent()).singleElement().satisfies(m -> {
			assertThat(m.token()).isEqualTo(authorDevice.token());
			assertThat(m.title()).isEqualTo("내 글에 새 댓글이 달렸어요");
			assertThat(m.data()).containsEntry("target", "post.detail").containsEntry("postId", post1.value().toString());
		});

		// 제3자가 이웃 댓글에 답글 → 게시글 작성자(게시글 문구) + 상위 댓글 작성자(답글 문구)
		pushes.clear();
		PostId post2 = newPost();
		alerts.comment(comment(third, post2, author, neighbor));
		assertThat(pushes.sentTo(authorDevice.token())).singleElement()
			.satisfies(m -> assertThat(m.title()).isEqualTo("내 글에 새 댓글이 달렸어요"));
		assertThat(pushes.sentTo(neighborDevice.token())).singleElement()
			.satisfies(m -> assertThat(m.title()).isEqualTo("내 댓글에 답글이 달렸어요"));
		assertThat(pushes.sentTo(thirdDevice.token())).isEmpty();

		// 게시글 작성자 = 상위 댓글 작성자 → 1회, 게시글 작성자 문구
		pushes.clear();
		PostId post3 = newPost();
		alerts.comment(comment(third, post3, author, author));
		assertThat(pushes.sent()).singleElement().satisfies(m -> {
			assertThat(m.token()).isEqualTo(authorDevice.token());
			assertThat(m.title()).isEqualTo("내 글에 새 댓글이 달렸어요");
		});

		// 내 글에 내가 단 댓글·내 댓글에 내가 단 답글 → 아무에게도 없음
		pushes.clear();
		alerts.comment(comment(author, newPost(), author, null));
		alerts.comment(comment(author, newPost(), author, author));
		assertThat(pushes.sent()).isEmpty();

		// 로그아웃(연결 해제)한 기기에는 가지 않는다
		confirm(neighborDevice, null);
		alerts.comment(comment(third, newPost(), neighbor, null));
		assertThat(pushes.sent()).isEmpty();
		assertThat(pushes.sent().stream().map(PushMessage::body)).noneMatch(b -> b.contains("테스트"));
	}

	/** BR-NOTI-07: 댓글 알림 쿨다운은 (게시글, 기기) 단위 comment.cooldown(1시간). 다른 게시글은 따로. 이벤트 재전달은 중복 발송 0건. */
	@Test
	void commentCooldownPerPostAndRedeliveryIsIdempotent() throws Exception {
		clock.set(NOON);
		AuthTestClient.Session author = member();
		AuthTestClient.Session neighbor = member();
		DeviceHandle device = registerDevice("cool-" + UUID.randomUUID(), author);
		PostId post = newPost();

		CommentCreated first = comment(neighbor, post, author, null);
		alerts.comment(first);
		alerts.comment(first);
		assertThat(pushes.sent()).hasSize(1);

		clock.advance(Duration.ofMinutes(30));
		alerts.comment(comment(neighbor, post, author, null));
		alerts.comment(comment(neighbor, newPost(), author, null));
		assertThat(pushes.sent()).hasSize(2);
		assertThat(deliveries("COMMENT")).extracting(r -> r.get("skip_reason")).contains("COOLDOWN");

		clock.advance(Duration.ofMinutes(31));
		alerts.comment(comment(neighbor, post, author, null));
		assertThat(pushes.sentTo(device.token())).hasSize(3);
	}

	/**
	 * FR-NOTI-11: 현장 제보 강수 알림. 제보자 본인에게 연결된 기기는 '생략(행위자 본인)'(legacy 05 확인 필요 9). 비·눈은 한 종류라 쿨다운을 같이 쓰고
	 * 문구만 다르다. 문구에 닉네임·글이 없다. 다른 동네 구독자에게는 가지 않는다.
	 */
	@Test
	void fieldReportAlertExcludesReporterAndSharesCooldownBetweenRainAndSnow() throws Exception {
		clock.set(NOON);
		DeviceHandle guest = guestDevice();
		subscribeFixed(guest, N1, null, false, true);
		AuthTestClient.Session reporter = member();
		neighborhoodApi.confirmCurrentNeighborhood(reporter.memberId(), INSIDE_N1).orElseThrow();
		DeviceHandle reporterDevice = registerDevice("reporter-" + UUID.randomUUID(), reporter);
		subscribeFollow(reporterDevice, null, false, true);
		DeviceHandle elsewhere = guestDevice();
		subscribeFixed(elsewhere, N2, null, false, true);

		alerts.fieldReportPrecipitation(fieldReport(PrecipitationType.RAIN, 3, reporter.memberId()));
		assertThat(pushes.sent()).singleElement().satisfies(m -> {
			assertThat(m.token()).isEqualTo(guest.token());
			assertThat(m.title()).isEqualTo(name(N1) + "에 지금 비가 온대요 ☔");
			assertThat(m.body()).isEqualTo("이웃 3명이 지금 비가 온다고 알려줬어요.");
			assertThat(m.data()).containsEntry("target", "home.field-report").containsEntry("neighborhoodId", N1.value());
		});
		assertThat(delivery("FIELD_REPORT_PRECIPITATION", reporterDevice.id()).get("skip_reason")).isEqualTo("ACTOR");

		// 1시간 뒤 눈: 비회원 기기는 쿨다운(비·눈 공유). 행위자였던 기기는 쿨다운을 잡지 않았으므로 이번 이벤트(제보자 아님)는 받는다
		clock.advance(Duration.ofHours(1));
		alerts.fieldReportPrecipitation(fieldReport(PrecipitationType.SNOW, 4));
		assertThat(pushes.sentTo(guest.token())).hasSize(1);
		assertThat(pushes.sentTo(reporterDevice.token())).singleElement()
			.satisfies(m -> assertThat(m.title()).isEqualTo(name(N1) + "에 지금 눈이 온대요 ❄"));
		assertThat(deliveries("FIELD_REPORT_PRECIPITATION")).extracting(r -> r.get("skip_reason")).contains("COOLDOWN");

		clock.advance(Duration.ofHours(2));
		alerts.fieldReportPrecipitation(fieldReport(PrecipitationType.SNOW, 4));
		assertThat(pushes.sentTo(guest.token())).last()
			.satisfies(m -> assertThat(m.title()).isEqualTo(name(N1) + "에 지금 눈이 온대요 ❄"));
		// 행위자였던 기기는 13시 발송의 쿨다운(16시까지) 중
		assertThat(pushes.sentTo(reporterDevice.token())).hasSize(1);
	}

	/**
	 * 심야 정책 3종 (BR-NOTI-08): 현장 제보·댓글 알림은 심야면 버리고(기록은 '생략(심야)'), 강수 예보 알림은 보류했다가 금지 종료 시각에
	 * 예상 시작 시각이 아직 미래면 보낸다(지났으면 버림). 정기 날씨 알림 예외는 DailyWeatherAlertTests.
	 */
	@Test
	void quietHoursDropFieldReportAndCommentButHoldPrecipitationForecast() throws Exception {
		clock.set(kst("2026-10-13T23:30"));
		DeviceHandle guest = guestDevice();
		subscribeFixed(guest, N1, null, true, true);
		AuthTestClient.Session author = member();
		registerDevice("night-" + UUID.randomUUID(), author);

		alerts.fieldReportPrecipitation(fieldReport(PrecipitationType.RAIN, 3));
		alerts.comment(comment(member(), newPost(), author, null));
		assertThat(pushes.sent()).isEmpty();
		assertThat(deliveries("FIELD_REPORT_PRECIPITATION")).singleElement()
			.satisfies(r -> assertThat(r).containsEntry("status", "SKIPPED").containsEntry("skip_reason", "QUIET_HOURS"));
		assertThat(deliveries("COMMENT")).singleElement()
			.satisfies(r -> assertThat(r).containsEntry("skip_reason", "QUIET_HOURS"));

		// 05:30 예보: 08:00 시작은 보류 → 07:00에 보냄, 06:30 시작은 07:00에 이미 지나 버림
		clock.set(kst("2026-10-14T05:30"));
		alerts.precipitationForecast(forecast(kst("2026-10-14T08:00")));
		DeviceHandle early = guestDevice();
		subscribeFixed(early, N2, null, true, false);
		alerts.precipitationForecast(new PrecipitationStartForecasted(UUID.randomUUID(), N2, kst("2026-10-14T06:30"), PrecipitationType.SNOW,
				null, clock.instant(), clock.instant()));
		assertThat(pushes.sent()).isEmpty();
		assertThat(deliveries("PRECIPITATION_FORECAST")).extracting(r -> r.get("status")).containsOnly("HELD");

		clock.set(kst("2026-10-14T06:00"));
		dispatcher.processDue();
		assertThat(pushes.sent()).isEmpty();

		clock.set(kst("2026-10-14T07:00"));
		dispatcher.processDue();
		assertThat(pushes.sent()).singleElement().satisfies(m -> {
			assertThat(m.token()).isEqualTo(guest.token());
			assertThat(m.title()).isEqualTo(name(N1) + " 비 소식 ☔");
			assertThat(m.body()).isEqualTo("오전 8시쯤부터 약한 비 예보가 있어요. 우산 챙기세요.");
			assertThat(m.data()).containsEntry("target", "home.hourly");
		});
		assertThat(delivery("PRECIPITATION_FORECAST", early.id())).containsEntry("status", "SKIPPED")
			.containsEntry("skip_reason", "QUIET_HOURS");
	}

	/** FR-NOTI-10: 낮에는 바로 보낸다. 강수 예보와 현장 제보 강수 알림은 서로의 쿨다운에 영향이 없다. 예상 시작이 지났거나 지연 한도를 넘긴 이벤트는 버린다. */
	@Test
	void precipitationForecastCooldownIsIndependentAndLateEventsAreDropped() throws Exception {
		clock.set(NOON);
		DeviceHandle guest = guestDevice();
		subscribeFixed(guest, N1, null, true, true);
		alerts.fieldReportPrecipitation(fieldReport(PrecipitationType.RAIN, 3));
		alerts.precipitationForecast(forecast(kst("2026-10-13T14:00")));
		assertThat(pushes.sent()).extracting(m -> m.data().get("kind"))
			.containsExactly("FIELD_REPORT_PRECIPITATION", "PRECIPITATION_FORECAST");

		clock.advance(Duration.ofHours(1));
		alerts.precipitationForecast(forecast(kst("2026-10-13T15:00")));
		assertThat(pushes.sent()).hasSize(2);

		pushes.clear();
		clock.advance(Duration.ofHours(4));
		alerts.precipitationForecast(forecast(clock.instant().minusSeconds(60)));
		alerts.precipitationForecast(new PrecipitationStartForecasted(UUID.randomUUID(), N1, clock.instant().plusSeconds(3600),
				PrecipitationType.RAIN, "비", clock.instant(), clock.instant().minus(Duration.ofMinutes(31))));
		assertThat(pushes.sent()).isEmpty();
	}

	/**
	 * BR-NOTI-11·12·13 (legacy 05 #1·#5): 한 기기의 실패가 다른 건을 막지 않는다. 무효 토큰 응답이면 즉시 그 기기를 발송 중지(다음 회차에서 빠짐)하고
	 * 쿨다운을 풀며, 일시 오류는 retry-backoff 뒤 재시도, max-retries를 넘기면 최종 실패.
	 */
	@Test
	void invalidTokenIsSuspendedImmediatelyAndTransientErrorsAreRetried_05_1() throws Exception {
		clock.set(NOON);
		DeviceHandle ok = guestDevice();
		DeviceHandle invalid = guestDevice();
		DeviceHandle flaky = guestDevice();
		DeviceHandle broken = guestDevice();
		for (DeviceHandle device : List.of(ok, invalid, flaky, broken)) {
			subscribeFixed(device, N1, null, false, true);
		}
		pushes.script(invalid.token(), PushResult.invalidToken("UNREGISTERED"));
		pushes.script(flaky.token(), PushResult.transientFailure("UNAVAILABLE"));
		pushes.script(broken.token(), PushResult.transientFailure("INTERNAL"), PushResult.transientFailure("INTERNAL"),
				PushResult.transientFailure("INTERNAL"));

		alerts.fieldReportPrecipitation(fieldReport(PrecipitationType.RAIN, 3));
		assertThat(pushes.sentTo(ok.token())).hasSize(1);
		assertThat(delivery("FIELD_REPORT_PRECIPITATION", invalid.id())).containsEntry("status", "FAILED")
			.containsEntry("skip_reason", "INVALID_DEVICE");
		assertThat(jdbc.sql("SELECT status || ':' || suspended_reason FROM noti_device WHERE id = :id")
			.param("id", invalid.id())
			.query(String.class)
			.single()).isEqualTo("SUSPENDED:INVALID_TOKEN");
		assertThat(delivery("FIELD_REPORT_PRECIPITATION", flaky.id())).containsEntry("status", "RETRY_WAIT");

		clock.advance(Duration.ofSeconds(30));
		dispatcher.processDue();
		assertThat(pushes.sentTo(flaky.token())).hasSize(1);
		assertThat(delivery("FIELD_REPORT_PRECIPITATION", flaky.id())).containsEntry("status", "SUCCEEDED");

		clock.advance(Duration.ofSeconds(60));
		dispatcher.processDue();
		assertThat(delivery("FIELD_REPORT_PRECIPITATION", broken.id())).containsEntry("status", "FAILED")
			.containsEntry("skip_reason", "RETRY_EXHAUSTED");
		assertThat(((Number) delivery("FIELD_REPORT_PRECIPITATION", broken.id()).get("attempts")).intValue()).isEqualTo(3);

		// 다음 이벤트: 무효 기기는 대상에서 빠지고, 최종 실패한 기기는 쿨다운이 풀려 받는다(실패에도 쿨다운 걸리던 문제 방지)
		pushes.clear();
		clock.advance(Duration.ofMinutes(10));
		alerts.fieldReportPrecipitation(fieldReport(PrecipitationType.RAIN, 5));
		assertThat(pushes.sent()).extracting(PushMessage::token).containsExactly(broken.token());

		// 새 토큰으로 기기 확인하면 다시 활성
		confirm(invalid.id(), invalid.key(), "renewed-" + UUID.randomUUID(), null)
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status")
				.value("ACTIVE"));
	}

	/** 푸시 자격이 없으면 발송하지 않고 '생략(발송 비활성)'으로 남긴다(기동·이벤트 처리는 정상). */
	@Test
	void disabledPushRecordsSkip() throws Exception {
		clock.set(NOON);
		pushes.setEnabled(false);
		DeviceHandle guest = guestDevice();
		subscribeFixed(guest, N1, null, false, true);
		alerts.fieldReportPrecipitation(fieldReport(PrecipitationType.RAIN, 3));
		assertThat(delivery("FIELD_REPORT_PRECIPITATION", guest.id())).containsEntry("skip_reason", "PUSH_DISABLED");
	}

}
