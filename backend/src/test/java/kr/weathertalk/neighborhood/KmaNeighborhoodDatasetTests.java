package kr.weathertalk.neighborhood;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import kr.weathertalk.IntegrationTest;
import kr.weathertalk.neighborhood.internal.dataset.DatasetLoadReport;
import kr.weathertalk.neighborhood.internal.dataset.NeighborhoodDatasetLoader;
import kr.weathertalk.shared.Ids.NeighborhoodId;

/**
 * 실제 기상청 전국 행정동 자료(ADR 0016, classpath neighborhood/kma) 적재. 좌표 판정은 앱이 같은 자료로 한다(ADR 0009).
 * 다른 테스트는 SAMPLE 데이터를 쓰므로 끝나면 SAMPLE 기준 버전으로 되돌린다(전국 동네는 폐지 상태로 남는다).
 */
@IntegrationTest
class KmaNeighborhoodDatasetTests {

	static final String KMA_CODES = "neighborhood/kma/kma-administrative-dong-2607.csv";

	@Autowired
	NeighborhoodDatasetLoader loader;

	@Autowired
	NeighborhoodApi neighborhoodApi;

	@AfterEach
	void restore() {
		NeighborhoodTestData.loadBase(loader);
	}

	@Test
	void loadsNationwideDataset() {
		DatasetLoadReport report = loader.load("KMA-2607-test", NeighborhoodTestData.resource(KMA_CODES));
		System.out.println("기상청 전국 행정동 적재: " + report.summary());
		report.gridWarnings().forEach(w -> System.out.println("  격자 검증 경고: " + w));

		assertThat(report.total()).isEqualTo(3564);
		Neighborhood found = neighborhoodApi.findNeighborhood(new NeighborhoodId("1111051500")).orElseThrow();
		assertThat(found.fullName()).isEqualTo("서울특별시 종로구 청운효자동");
		assertThat(found.forecastGrid()).isEqualTo(new ForecastGrid(60, 127));
	}

}
