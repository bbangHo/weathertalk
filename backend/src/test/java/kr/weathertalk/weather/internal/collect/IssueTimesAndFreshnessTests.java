package kr.weathertalk.weather.internal.collect;

import static kr.weathertalk.weather.WeatherStubs.kst;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import kr.weathertalk.weather.Freshness;
import kr.weathertalk.weather.WeatherPropertiesFixture;

/**
 * 발표 시각 선정 (BR-WEA-02)과 신선도 판정 (BR-WEA-03~05). 제공 지연은 설정 초기값(실황·단기 10분, 중기·자외선 30분, 대기질 20분).
 */
class IssueTimesAndFreshnessTests {

	final IssueTimes issueTimes = new IssueTimes(WeatherPropertiesFixture.defaults());

	final FreshnessRules freshness = new FreshnessRules(issueTimes, WeatherPropertiesFixture.defaults());

	/** legacy 04-#9: 발표 시각 + 제공 지연이 지나기 전에는 직전 발표분이 최신이다. 00:00~02:XX의 단기예보는 전날 23시. */
	@ParameterizedTest(name = "{0} {1} → {2}")
	@CsvSource({ "SHORT_TERM, 2026-10-13T02:09, 2026-10-12T23:00", "SHORT_TERM, 2026-10-13T02:10, 2026-10-13T02:00",
			"SHORT_TERM, 2026-10-13T00:30, 2026-10-12T23:00", "SHORT_TERM, 2026-10-13T13:59, 2026-10-13T11:00",
			"OBSERVATION, 2026-10-13T09:09, 2026-10-13T08:00", "OBSERVATION, 2026-10-13T09:10, 2026-10-13T09:00",
			"MID_LAND, 2026-10-13T06:29, 2026-10-12T18:00", "MID_LAND, 2026-10-13T06:30, 2026-10-13T06:00",
			"UV, 2026-10-13T18:45, 2026-10-13T18:00", "AIR, 2026-10-13T09:19, 2026-10-13T08:00" })
	void latestIssueRespectsAvailabilityDelay_04_9(CollectionKind kind, String now, String expected) {
		assertThat(issueTimes.latest(kind, kst(now))).isEqualTo(kst(expected));
	}

	@Test
	void previousIssue() {
		assertThat(issueTimes.previous(CollectionKind.SHORT_TERM, kst("2026-10-13T02:00"))).isEqualTo(kst("2026-10-12T23:00"));
		assertThat(issueTimes.previous(CollectionKind.MID_LAND, kst("2026-10-13T06:00"))).isEqualTo(kst("2026-10-12T18:00"));
		assertThat(issueTimes.previous(CollectionKind.OBSERVATION, kst("2026-10-13T00:00"))).isEqualTo(kst("2026-10-12T23:00"));
	}

	/** legacy 04-#8: 판정은 시각만으로 한다(같은 입력이면 몇 번을 조회해도 같은 결과, 조회가 만료를 늦추지 않음). */
	@Test
	void freshnessThreeLevelsByIssueTime_04_8() {
		// 실황: 최신 정상 / max-staleness(2h) 이내 지연 / 넘으면 없음
		assertThat(freshness.observation(kst("2026-10-13T09:00"), kst("2026-10-13T09:15"))).isEqualTo(Freshness.FRESH);
		assertThat(freshness.observation(kst("2026-10-13T09:00"), kst("2026-10-13T11:00"))).isEqualTo(Freshness.DELAYED);
		assertThat(freshness.observation(kst("2026-10-13T09:00"), kst("2026-10-13T11:01"))).isEqualTo(Freshness.NONE);
		assertThat(freshness.observation(null, kst("2026-10-13T11:01"))).isEqualTo(Freshness.NONE);
		// 단기: 남은 칸이 없으면 지연이 아니라 없음
		assertThat(freshness.shortTerm(kst("2026-10-13T08:00"), kst("2026-10-13T12:00"), true)).isEqualTo(Freshness.DELAYED);
		assertThat(freshness.shortTerm(kst("2026-10-13T08:00"), kst("2026-10-13T12:00"), false)).isEqualTo(Freshness.NONE);
		assertThat(freshness.shortTerm(kst("2026-10-13T08:00"), kst("2026-10-13T14:01"), true)).isEqualTo(Freshness.NONE);
		// 자외선·중기: 최신 정상, 직전 지연, 그 이전 없음
		assertThat(freshness.byIssue(CollectionKind.UV, kst("2026-10-13T06:00"), kst("2026-10-13T12:00"))).isEqualTo(Freshness.FRESH);
		assertThat(freshness.byIssue(CollectionKind.UV, kst("2026-10-13T06:00"), kst("2026-10-13T19:00"))).isEqualTo(Freshness.DELAYED);
		assertThat(freshness.byIssue(CollectionKind.UV, kst("2026-10-12T18:00"), kst("2026-10-13T19:00"))).isEqualTo(Freshness.NONE);
		// 대기질: 측정 시각 max-staleness(3h)
		assertThat(freshness.air(kst("2026-10-13T08:00"), kst("2026-10-13T09:19"))).isEqualTo(Freshness.FRESH);
		assertThat(freshness.air(kst("2026-10-13T08:00"), kst("2026-10-13T10:30"))).isEqualTo(Freshness.DELAYED);
		assertThat(freshness.air(kst("2026-10-13T08:00"), kst("2026-10-13T11:01"))).isEqualTo(Freshness.NONE);
	}

}
