package kr.weathertalk.weather;

import static kr.weathertalk.neighborhood.NeighborhoodTestData.N1;
import static kr.weathertalk.weather.WeatherStubs.kst;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import kr.weathertalk.IntegrationTest;
import kr.weathertalk.shared.Ids.MemberId;

/**
 * 현장제보강수기준도달함 (BR-WEA-17, FR-WEA-14 규칙 5). rain-threshold 3명, rain-window 30분, rain-suppress 3시간.
 * 레거시는 게시글 '비와요' 하나로 우산 알림이 나갔고 작성자 본인도 받았다(legacy 05-#9·#10) — 리부트는 서로 다른 회원 수 기준,
 * (동네, 강수 형태) 단위 억제, 제보 회원 ID 동봉(본인 제외는 구독자 몫)으로 바꿨다.
 */
@IntegrationTest
class FieldReportPrecipitationTests extends FieldReportTestBase {

	static final Instant NOW = kst("2026-10-13T10:00");

	@Test
	void eventIsPublishedOnceWhenThreeDifferentMembersReportRain() {
		clock.set(NOW);
		List<MemberId> members = newMembers(4);
		// 한 회원이 여러 번 눌러도 1명이다
		report(members.get(0), FieldReportKind.RAIN);
		report(members.get(0), FieldReportKind.RAIN);
		report(members.get(1), FieldReportKind.RAIN);
		assertThat(events(FieldReportPrecipitationReached.class)).isEmpty();

		clock.set(NOW.plus(Duration.ofMinutes(5)));
		report(members.get(2), FieldReportKind.RAIN);
		List<FieldReportPrecipitationReached> published = events(FieldReportPrecipitationReached.class);
		assertThat(published).hasSize(1);
		FieldReportPrecipitationReached event = published.getFirst();
		assertThat(event.eventId()).isNotNull();
		assertThat(event.neighborhoodId()).isEqualTo(N1);
		assertThat(event.precipitationType()).isEqualTo(PrecipitationType.RAIN);
		assertThat(event.reachedAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
		assertThat(event.reporterCount()).isEqualTo(3);
		assertThat(event.reporterIds()).containsExactlyInAnyOrder(members.get(0), members.get(1), members.get(2));

		// 억제 기간 안의 추가 제보는 다시 발행하지 않는다
		report(members.get(3), FieldReportKind.RAIN);
		assertThat(events(FieldReportPrecipitationReached.class)).hasSize(1);
		// M7: notification이 구독해 이벤트 발행 저장소에 영속화되고, 처리가 끝나면 완료로 기록된다
		assertPublicationsCompleted(FieldReportPrecipitationReached.class);
	}

	/** 비와 눈은 합산하지 않고 강수 형태별로 따로 판정·억제한다. */
	@Test
	void rainAndSnowAreJudgedSeparately() {
		clock.set(NOW);
		List<MemberId> members = newMembers(5);
		report(members.get(0), FieldReportKind.RAIN);
		report(members.get(1), FieldReportKind.RAIN);
		report(members.get(2), FieldReportKind.SNOW);
		report(members.get(3), FieldReportKind.SNOW);
		assertThat(events(FieldReportPrecipitationReached.class)).isEmpty();

		report(members.get(4), FieldReportKind.SNOW);
		assertThat(events(FieldReportPrecipitationReached.class)).singleElement()
			.satisfies(e -> {
				assertThat(e.precipitationType()).isEqualTo(PrecipitationType.SNOW);
				assertThat(e.reporterCount()).isEqualTo(3);
			});
	}

	/** rain-window(30분, 경계 포함) 밖 제보·취소된 제보는 세지 않는다. 다른 종류로 대체한 회원도 빠진다. */
	@Test
	void reportsOutsideRainWindowCanceledOrReplacedAreNotCounted() {
		clock.set(NOW);
		List<MemberId> members = newMembers(6);
		report(members.get(0), FieldReportKind.RAIN); // 10:00 — 10:31에는 창 밖
		clock.set(NOW.plus(Duration.ofMinutes(1)));
		FieldReport canceled = report(members.get(2), FieldReportKind.RAIN).report();
		fieldReports.cancel(members.get(2), canceled.reportId());
		report(members.get(3), FieldReportKind.RAIN);
		report(members.get(3), FieldReportKind.CLEAR); // 대체: 이제 맑음
		report(members.get(1), FieldReportKind.RAIN); // 10:01 — 10:31에 정확히 30분(포함)
		assertThat(events(FieldReportPrecipitationReached.class)).isEmpty(); // 10:00, 10:01 두 명

		clock.set(NOW.plus(Duration.ofMinutes(31)));
		report(members.get(4), FieldReportKind.RAIN);
		assertThat(events(FieldReportPrecipitationReached.class)).isEmpty(); // 10:01, 10:31 두 명

		report(members.get(5), FieldReportKind.RAIN);
		assertThat(events(FieldReportPrecipitationReached.class)).singleElement()
			.satisfies(e -> assertThat(e.reporterIds()).containsExactlyInAnyOrder(members.get(1), members.get(4),
					members.get(5)));
	}

	/**
	 * rain-suppress(3시간) 이내(경계 포함)는 재발행하지 않고, 넘으면 다시 발행한다. 기준 도달 뒤 취소로 인원이 줄어도 발행 기록(억제)은 그대로다
	 * (FR-WEA-14 규칙 5, Q56).
	 */
	@Test
	void republishIsSuppressedUntilRainSuppressPassesEvenIfReportsAreCanceled() {
		clock.set(NOW);
		List<MemberId> first = newMembers(3);
		FieldReport last = null;
		for (MemberId member : first) {
			last = report(member, FieldReportKind.RAIN).report();
		}
		assertThat(events(FieldReportPrecipitationReached.class)).hasSize(1);
		fieldReports.cancel(first.get(2), last.reportId());
		report(newMember(), FieldReportKind.RAIN); // 다시 3명이 돼도 억제 기간
		assertThat(events(FieldReportPrecipitationReached.class)).hasSize(1);

		Instant suppressEnd = NOW.plus(Duration.ofHours(3));
		clock.set(suppressEnd.minus(Duration.ofMinutes(10)));
		List<MemberId> second = newMembers(4);
		report(second.get(0), FieldReportKind.RAIN);
		report(second.get(1), FieldReportKind.RAIN);
		clock.set(suppressEnd);
		report(second.get(2), FieldReportKind.RAIN);
		assertThat(events(FieldReportPrecipitationReached.class)).hasSize(1);

		clock.set(suppressEnd.plusSeconds(1));
		report(second.get(3), FieldReportKind.RAIN);
		assertThat(events(FieldReportPrecipitationReached.class)).hasSize(2);
	}

	/** 동시에 제보가 몰려도 (동네, 강수 형태)마다 정확히 1회 발행한다(원자적 판정·발행 기록). */
	@Test
	void concurrentReportsPublishExactlyOncePerNeighborhoodAndPrecipitationType() throws Exception {
		clock.set(NOW);
		List<MemberId> rain = newMembers(8);
		List<MemberId> snow = newMembers(8);
		List<Runnable> tasks = new ArrayList<>();
		rain.forEach(m -> tasks.add(() -> report(m, FieldReportKind.RAIN)));
		snow.forEach(m -> tasks.add(() -> report(m, FieldReportKind.SNOW)));

		ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<?>> futures = new ArrayList<>();
			for (Runnable task : tasks) {
				futures.add(pool.submit(() -> {
					start.await();
					task.run();
					return null;
				}));
			}
			start.countDown();
			for (Future<?> future : futures) {
				future.get(30, TimeUnit.SECONDS);
			}
		}
		finally {
			pool.shutdownNow();
		}

		List<FieldReportPrecipitationReached> published = events(FieldReportPrecipitationReached.class);
		assertThat(published).extracting(FieldReportPrecipitationReached::precipitationType)
			.containsExactlyInAnyOrder(PrecipitationType.RAIN, PrecipitationType.SNOW);
		assertThat(published).allSatisfy(e -> assertThat(e.reporterCount()).isEqualTo(3));
		assertThat(jdbc.sql("SELECT count(*) FROM weather_field_report_precip_publication WHERE neighborhood_code = :n")
			.param("n", N1.value())
			.query(Integer.class)
			.single()).isEqualTo(2);
		assertThat(reportRows()).isEqualTo(16);
	}

}
