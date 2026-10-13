package kr.weathertalk.weather;

import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N7;
import static kr.weathertalk.weather.WeatherStubs.kst;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import kr.weathertalk.IntegrationTest;

/**
 * 부가 정보(대기질·자외선) 갱신 주기 — weather 남은 legacy 항목.
 * <ul>
 * <li>legacy 04-#12: 대기질은 하루 1회가 아니라 매시(+ weather.air.availability-delay) 갱신하고, 응답에 측정 시각을 싣는다.</li>
 * <li>legacy 04 FR-WEA-02 본문: 자외선 신선도는 원천 발표 주기(06·18시 + weather.uv.availability-delay)로 판정해, 같은 발표 주기 안의
 * 조회는 외부를 다시 부르지 않는다(레거시는 '3시간 경과' 조건이 거의 늘 참이라 조회마다 호출).</li>
 * </ul>
 */
@IntegrationTest
class SupplementaryRefreshCycleTests extends WeatherTestBase {

	@Test
	void airQualityIsRefreshedHourlyAndCarriesMeasurementTime_04_12() {
		stubs.nearbyStations("샘플측정소A");
		stubs.measurements("샘플측정소A", List.<String[]>of(new String[] { "2026-10-13 09:00", "45", "20", "2", "2", "1" }));
		clock.set(kst("2026-10-13T09:25"));
		assertThat(weatherApi.findCurrentObservation(N7).airQuality().measuredAt()).isEqualTo(kst("2026-10-13T09:00"));

		clock.set(kst("2026-10-13T09:55"));
		weatherApi.findCurrentObservation(N7);
		weatherApi.findCurrentObservation(N7);
		server.verify(exactly(1), getRequestedFor(urlPathEqualTo(WeatherStubs.MEASURE)));

		// 다음 시(10:00 + 제공 지연 20분)가 되면 다시 받는다
		stubs.measurements("샘플측정소A", List.<String[]>of(new String[] { "2026-10-13 10:00", "60", "30", "2", "3", "1" },
				new String[] { "2026-10-13 09:00", "45", "20", "2", "2", "1" }));
		clock.set(kst("2026-10-13T10:25"));
		AirQuality air = weatherApi.findCurrentObservation(N7).airQuality();
		server.verify(exactly(2), getRequestedFor(urlPathEqualTo(WeatherStubs.MEASURE)));
		assertThat(air.measuredAt()).isEqualTo(kst("2026-10-13T10:00"));
		assertThat(air.combinedGrade()).isEqualTo(AirGrade.BAD);
		assertThat(air.freshness()).isEqualTo(Freshness.FRESH);
	}

	@Test
	void uvIsNotRefetchedWithinTheSameIssueCycle_04_FR_WEA_02() {
		stubs.uv(UV_AREA, kst("2026-10-13T06:00"), 1, 3, 5, 6, 4);
		for (String at : List.of("2026-10-13T09:15", "2026-10-13T12:00", "2026-10-13T15:40", "2026-10-13T17:59")) {
			clock.set(kst(at));
			assertThat(weatherApi.findCurrentObservation(N7).uv().issuedAt()).isEqualTo(kst("2026-10-13T06:00"));
		}
		server.verify(exactly(1), getRequestedFor(urlPathEqualTo(WeatherStubs.UV)));
	}

}
