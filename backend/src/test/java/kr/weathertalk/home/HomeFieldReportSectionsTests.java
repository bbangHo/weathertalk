package kr.weathertalk.home;

import static kr.weathertalk.weather.WeatherStubs.N7_GRID;
import static kr.weathertalk.weather.WeatherStubs.kst;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import kr.weathertalk.AuthTestClient;
import kr.weathertalk.IntegrationTest;
import kr.weathertalk.weather.FieldReportKind;
import kr.weathertalk.weather.FieldReportTestBase;

/**
 * 홈의 예보 vs 주민 차이 배지·현장 제보 현황 섹션 (07 §5 SCR-01 3·6번). 판정·집계는 weather 결과 그대로 표시만 한다.
 */
@IntegrationTest
class HomeFieldReportSectionsTests extends FieldReportTestBase {

	@Test
	void homeShowsWeatherJudgedDivergenceBadgeAndFieldReportStatusWithMyReport() throws Exception {
		clock.set(kst("2026-10-13T09:15"));
		stubs.observation(N7_GRID, kst("2026-10-13T09:00"), 18.0);
		stubs.shortTerm(N7_GRID, kst("2026-10-13T08:00"), 30, at -> Map.of());

		// 제보 전: 두 섹션 모두 데이터 없음(실패 아님)
		mvc.perform(get("/api/v1/home").param("neighborhoodId", "9900000007"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.divergence.status").value("EMPTY"))
			.andExpect(jsonPath("$.fieldReports.status").value("EMPTY"));

		AuthTestClient.Session session = new AuthTestClient(mvc).signup();
		submitViaApi(session, newKey(), "RAIN", AT_N7).andExpect(status().isOk());
		reportAtN7(newMember(), FieldReportKind.RAIN);
		reportAtN7(newMember(), FieldReportKind.RAIN);

		mvc.perform(get("/api/v1/home").param("neighborhoodId", "9900000007")
			.header(HttpHeaders.AUTHORIZATION, session.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.divergence.status").value("OK"))
			.andExpect(jsonPath("$.divergence.data.axes[0].axis").value("PRECIPITATION"))
			.andExpect(jsonPath("$.divergence.data.axes[0].badge").value(true))
			.andExpect(jsonPath("$.divergence.data.axes[0].basisSource").value("OBSERVATION"))
			.andExpect(jsonPath("$.fieldReports.status").value("OK"))
			.andExpect(jsonPath("$.fieldReports.data.axes[0].reporterCount").value(3))
			.andExpect(jsonPath("$.fieldReports.data.myReports[0].kind").value("RAIN"))
			.andExpect(jsonPath("$.fieldReports.data.myReports[0].cancelableUntil").value("2026-10-13T00:25:00Z"));
	}

}
