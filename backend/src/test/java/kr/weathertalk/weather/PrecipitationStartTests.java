package kr.weathertalk.weather;

import static kr.weathertalk.neighborhood.NeighborhoodTestData.N7;
import static kr.weathertalk.weather.WeatherStubs.N7_GRID;
import static kr.weathertalk.weather.WeatherStubs.kst;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import kr.weathertalk.IntegrationTest;

/**
 * 강수 시작 판정 → 강수시작예보됨 (FR-WEA-09, BR-WEA-20). lead-window 3시간, min-pop 60%, same-episode-tolerance 2시간,
 * dry-gap 3시간. 단기예보·실황 수집 성공 직후 그 격자(98·76)의 활성 동네(동네 7)마다 판정한다.
 */
@IntegrationTest
class PrecipitationStartTests extends FieldReportTestBase {

	@BeforeEach
	void onlyThisTestActivatesNeighborhood7() {
		// 다른 테스트가 남긴 현재·관심 동네 7을 지워 이 테스트가 정한 활성 여부만 남긴다
		jdbc.sql("DELETE FROM nbr_current_neighborhood WHERE neighborhood_code = :code").param("code", N7.value()).update();
		jdbc.sql("DELETE FROM nbr_interest_neighborhood WHERE neighborhood_code = :code").param("code", N7.value()).update();
	}

	private void activateN7() {
		neighborhoodApi.confirmCurrentNeighborhood(newMember(), AT_N7).orElseThrow();
	}

	private void observation(String hour, String pty) {
		stubs.observation(N7_GRID, kst(hour), Map.of("T1H", "15", "REH", "70", "WSD", "1.0", "RN1", "0", "PTY", pty));
	}

	private void shortTerm(String issuedAt, Function<ZonedDateTime, Map<String, String>> slots) {
		stubs.shortTerm(N7_GRID, kst(issuedAt), 30, slots);
	}

	/** 그 시(時)에만 비(강수확률 pop). */
	private static Function<ZonedDateTime, Map<String, String>> rainAt(int hour, String pop) {
		return at -> at.getHour() == hour && at.getDayOfMonth() == 13
				? Map.of("PTY", "1", "POP", pop, "PCP", "2.0mm") : Map.of();
	}

	/** 현재 관측 조회가 실황·단기예보를 요청 시 수집하고, 각 수집 성공 직후 강수 시작 판정이 돈다. */
	private void collect() {
		weatherApi.findCurrentObservation(N7);
	}

	private List<PrecipitationStartForecasted> published() {
		return events(PrecipitationStartForecasted.class).stream()
			.filter(e -> e.neighborhoodId().equals(N7))
			.toList();
	}

	/** legacy 04-#17: 지금 이후 가장 이른 강수 칸(예보 조회와 같은 사본)이 예상 시작 시각이다. 같은 구간은 다시 수집돼도 1번만. */
	@Test
	void publishesEarliestRainStartOnceForTheSameEpisode_04_17() {
		clock.set(kst("2026-10-13T08:15"));
		activateN7();
		observation("2026-10-13T08:00", "0");
		shortTerm("2026-10-13T08:00", at -> at.getHour() == 10 || at.getHour() == 11
				? Map.of("PTY", "1", "POP", "70", "PCP", "2.0mm") : Map.of());

		collect();

		assertThat(published()).singleElement().satisfies(e -> {
			assertThat(e.eventId()).isNotNull();
			assertThat(e.expectedStartAt()).isEqualTo(kst("2026-10-13T10:00"));
			assertThat(e.precipitationType()).isEqualTo(PrecipitationType.RAIN);
			assertThat(e.precipitationPhrase()).isEqualTo("약한 비");
			assertThat(e.basisIssuedAt()).isEqualTo(kst("2026-10-13T08:00"));
		});

		// 09:15 새 실황 수집 → 다시 판정해도 같은 구간
		clock.set(kst("2026-10-13T09:15"));
		observation("2026-10-13T09:00", "0");
		collect();
		assertThat(published()).hasSize(1);
		// 구독자(M7 notification)가 없는 동안은 이벤트 발행 저장소에 쌓이지 않는다
		assertThat(storedPublications(PrecipitationStartForecasted.class)).isZero();
	}

	/** 조건 2: 이미 비가 오는 중(실황 강수 형태 있음)이면 '시작' 예보가 아니다. */
	@Test
	void doesNotPublishWhileAlreadyRaining() {
		clock.set(kst("2026-10-13T08:15"));
		activateN7();
		observation("2026-10-13T08:00", "1");
		shortTerm("2026-10-13T08:00", rainAt(10, "80"));

		collect();

		assertThat(published()).isEmpty();
	}

	/** 조건 1: 강수확률 min-pop(60%) 이상(경계 포함)만. */
	@Test
	void popThresholdBoundary() {
		clock.set(kst("2026-10-13T08:15"));
		activateN7();
		observation("2026-10-13T08:00", "0");
		shortTerm("2026-10-13T08:00", rainAt(10, "59"));
		collect();
		assertThat(published()).isEmpty();

		clock.set(kst("2026-10-13T11:15"));
		observation("2026-10-13T11:00", "0");
		shortTerm("2026-10-13T11:00", rainAt(13, "60"));
		collect();
		assertThat(published()).singleElement()
			.satisfies(e -> assertThat(e.expectedStartAt()).isEqualTo(kst("2026-10-13T13:00")));
	}

	/** 조건 1: lead-window(3시간) 안의 칸만. 08:15 기준 11:00은 포함, 12:00은 제외. */
	@Test
	void onlyRainWithinLeadWindowCounts() {
		clock.set(kst("2026-10-13T08:15"));
		activateN7();
		observation("2026-10-13T08:00", "0");
		shortTerm("2026-10-13T08:00", rainAt(12, "90"));
		collect();
		assertThat(published()).isEmpty();

		clock.set(kst("2026-10-13T09:15"));
		observation("2026-10-13T09:00", "0");
		collect(); // 같은 발표분이라도 실황 수집이 판정을 다시 돌린다: 이제 12:00이 3시간 안
		assertThat(published()).singleElement()
			.satisfies(e -> assertThat(e.expectedStartAt()).isEqualTo(kst("2026-10-13T12:00")));
	}

	/** Q57: 새 발표에서 강수 예보가 사라져도 취소 이벤트는 없고, 발행 기록은 남아 같은 구간이 다시 예보돼도 재발행하지 않는다. */
	@Test
	void forecastDisappearingPublishesNothing() {
		clock.set(kst("2026-10-13T08:15"));
		activateN7();
		observation("2026-10-13T08:00", "0");
		shortTerm("2026-10-13T08:00", rainAt(10, "70"));
		collect();
		assertThat(published()).hasSize(1);

		clock.set(kst("2026-10-13T09:15"));
		observation("2026-10-13T09:00", "0");
		collect();
		// 11시 발표: 비 없음
		clock.set(kst("2026-10-13T11:15"));
		observation("2026-10-13T11:00", "0");
		shortTerm("2026-10-13T11:00", at -> Map.of());
		collect();
		assertThat(published()).hasSize(1);
		assertThat(episodes()).isEqualTo(1);
	}

	/**
	 * 같은 강수 구간: 예상 시작 차이 same-episode-tolerance(2시간) 이내는 재발행 안 함. 비가 시작한 뒤 잠깐 그쳤다가 dry-gap 전에 다시 오는 비도
	 * 같은 구간. 강수 없음이 dry-gap 이상 이어지면 구간이 끝나 새 강수는 새로 발행한다.
	 */
	@Test
	void sameEpisodeToleranceAndDryGap() {
		clock.set(kst("2026-10-13T08:15"));
		activateN7();
		observation("2026-10-13T08:00", "0");
		shortTerm("2026-10-13T08:00", rainAt(10, "70"));
		collect();
		assertThat(published()).hasSize(1);

		// 10:15 실제로 비가 온다(구간 진행 중 — 강수 확인 시각 기록)
		clock.set(kst("2026-10-13T10:15"));
		observation("2026-10-13T10:00", "1");
		collect();

		// 11:15 잠깐 그침. 11시 발표가 13시 비 예보: 마지막 강수 확인(10:15)에서 dry-gap(3시간) 전이라 같은 구간
		clock.set(kst("2026-10-13T11:15"));
		observation("2026-10-13T11:00", "0");
		shortTerm("2026-10-13T11:00", rainAt(13, "70"));
		collect();
		assertThat(published()).hasSize(1);

		// 14:15 발표 14시: 10:15 이후 강수 없음이 3시간 넘게 이어져 구간 종료 → 16시 비는 새 구간
		clock.set(kst("2026-10-13T14:15"));
		observation("2026-10-13T14:00", "0");
		shortTerm("2026-10-13T14:00", rainAt(16, "70"));
		collect();
		assertThat(published()).hasSize(2);
		assertThat(published().get(1).expectedStartAt()).isEqualTo(kst("2026-10-13T16:00"));
		assertThat(published().get(1).basisIssuedAt()).isEqualTo(kst("2026-10-13T14:00"));
	}

	/** 대상은 활성 동네뿐이다(BR-WEA-20). */
	@Test
	void inactiveNeighborhoodIsNotJudged() {
		clock.set(kst("2026-10-13T08:15"));
		observation("2026-10-13T08:00", "0");
		shortTerm("2026-10-13T08:00", rainAt(10, "90"));
		collect();
		assertThat(published()).isEmpty();
		assertThat(episodes()).isZero();
	}

	private int episodes() {
		return jdbc.sql("SELECT count(*) FROM weather_precip_episode WHERE neighborhood_code = :code")
			.param("code", N7.value())
			.query(Integer.class)
			.single();
	}

}
