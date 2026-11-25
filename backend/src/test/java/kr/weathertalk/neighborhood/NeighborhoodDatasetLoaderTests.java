package kr.weathertalk.neighborhood;

import static kr.weathertalk.neighborhood.NeighborhoodTestData.N1;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N2;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N7;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import kr.weathertalk.IntegrationTest;
import kr.weathertalk.MutableClock;
import kr.weathertalk.neighborhood.internal.dataset.DatasetLoadReport;
import kr.weathertalk.neighborhood.internal.dataset.NeighborhoodDatasetLoader;
import kr.weathertalk.shared.Ids.NeighborhoodId;

/**
 * 동네 기준 데이터 적재 (02 §3.2, BR-NBR-03, ADR 0016): 버전 단위 통째 적재, 비교 보고서(격자 검증 경고 포함), 폐지 처리,
 * 멱등, 코드 유일, 선택 입력인 경계.
 */
@IntegrationTest
class NeighborhoodDatasetLoaderTests {

	static final String HEADER = "code,sido_name,sigungu_name,eupmyeondong_name,grid_nx,grid_ny,longitude,latitude,location_updated\n";

	@Autowired
	NeighborhoodDatasetLoader loader;

	@Autowired
	NeighborhoodApi neighborhoodApi;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MutableClock clock;

	@BeforeEach
	void setUp() {
		clock.reset();
		NeighborhoodTestData.loadBase(loader);
	}

	@AfterEach
	void restore() {
		NeighborhoodTestData.loadBase(loader);
	}

	/** legacy 04-#25: 같은 데이터를 다시 적재해도 결과가 같다(중복 생성 없음). */
	@Test
	void legacy04_25_reloadingSameDatasetChangesNothing() {
		Integer before = jdbc.queryForObject("SELECT count(*) FROM nbr_neighborhood", Integer.class);

		DatasetLoadReport report = NeighborhoodTestData.loadBase(loader);

		assertThat(report.noChanges()).isTrue();
		assertThat(report.total()).isEqualTo(7);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM nbr_neighborhood", Integer.class)).isEqualTo(before);
	}

	/** ADR 0016: 파일 격자를 그대로 쓰고, 계산식 결과와 다르면 비교 보고서에 경고만 남긴다. */
	@Test
	void fileGridIsKeptAndMismatchWithFormulaIsReportedAsWarning() {
		DatasetLoadReport report = NeighborhoodTestData.loadBase(loader);

		assertThat(report.gridWarnings()).singleElement().asString().startsWith("9900000007 파일(98,76)");
		assertThat(neighborhoodApi.findNeighborhood(N7).orElseThrow().forecastGrid()).isEqualTo(new ForecastGrid(98, 76));
	}

	@Test
	void newVersionReportsAddedChangedAndRetired() {
		String codes = HEADER + "9900000001,샘플시,가상구,새이름1동,,,127.005,37.505,\n"
				+ "9900000002,샘플시,가상구,가상2동,,,127.015,37.505,\n" + "9900000008,샘플시,새구,신설동,,,,,\n";
		// 신설 동네는 대표 좌표 없이 경계만 준다 → 경계 안의 점이 대표 좌표가 된다.
		String geo = """
				{"type":"FeatureCollection","features":[
				 {"type":"Feature","properties":{"code":"9900000008"},"geometry":{"type":"MultiPolygon","coordinates":[[[[127.100,37.600],[127.110,37.600],[127.110,37.610],[127.100,37.610],[127.100,37.600]]]]}}
				]}""";

		DatasetLoadReport report = loader.load("SAMPLE-test-2", NeighborhoodTestData.text(codes),
				NeighborhoodTestData.text(geo));

		assertThat(report.added()).containsExactly("9900000008");
		assertThat(report.changed()).containsExactly("9900000001");
		assertThat(report.retired()).containsExactly("9900000003", "9900000004", "9900000005", "9900000006",
				"9900000007");
		assertThat(neighborhoodApi.findNeighborhood(N1).orElseThrow().eupmyeondongName()).isEqualTo("새이름1동");
		assertThat(neighborhoodApi.findNeighborhood(N7).orElseThrow().status()).isEqualTo(NeighborhoodStatus.RETIRED);
		GeoPoint n8 = neighborhoodApi.findNeighborhood(new NeighborhoodId("9900000008")).orElseThrow().representativePoint();
		assertThat(n8.latitude()).isBetween(37.600, 37.610);
		assertThat(n8.longitude()).isBetween(127.100, 127.110);
		assertThat(jdbc.queryForObject("SELECT added_count FROM nbr_dataset_version WHERE version = 'SAMPLE-test-2'",
				Integer.class)).isEqualTo(1);

		// 기준 버전을 다시 적재하면 폐지됐던 동네가 되살아나고(변경으로 집계) 신설 동네는 폐지된다.
		DatasetLoadReport restored = NeighborhoodTestData.loadBase(loader);
		assertThat(restored.changed()).contains("9900000001", "9900000007");
		assertThat(restored.retired()).containsExactly("9900000008");
	}

	/** 경계(선택 입력)를 함께 적재하면 그 동네가 변경으로 집계된다. 판정에는 쓰지 않는다(앱이 대표점 최근접으로 판정, ADR 0009). */
	@Test
	void optionalBoundaryIsLoaded() {
		// N2에만 경계: 경도 127.008~127.020, 위도 37.500~37.510
		String geo = """
				{"type":"FeatureCollection","features":[
				 {"type":"Feature","properties":{"code":"9900000002"},"geometry":{"type":"Polygon","coordinates":[[[127.008,37.500],[127.020,37.500],[127.020,37.510],[127.008,37.510],[127.008,37.500]]]}}
				]}""";

		DatasetLoadReport report = loader.load("SAMPLE-test-boundary", NeighborhoodTestData.resource(NeighborhoodTestData.CODES),
				NeighborhoodTestData.text(geo));

		assertThat(report.changed()).containsExactly("9900000002");
		assertThat(jdbc.queryForObject("SELECT boundary IS NOT NULL FROM nbr_neighborhood WHERE code = '9900000002'",
				Boolean.class)).isTrue();
	}

	@Test
	void invalidInputChangesNothing() {
		String codes = HEADER + "9900000001,샘플시,가상구,가상1동,,,127.005,37.505,\n";
		String unknownCodeGeo = """
				{"type":"FeatureCollection","features":[
				 {"type":"Feature","properties":{"code":"9900000002"},"geometry":{"type":"Polygon","coordinates":[[[127.0,37.5],[127.01,37.5],[127.01,37.51],[127.0,37.5]]]}}
				]}""";
		String duplicateCodes = codes + "9900000001,샘플시,가상구,가상1동,,,127.005,37.505,\n";
		String withoutPoint = codes + "9900000002,샘플시,가상구,가상2동,,,,,\n";

		assertThatThrownBy(() -> loader.load("bad-1", NeighborhoodTestData.text(codes),
				NeighborhoodTestData.text(unknownCodeGeo))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> loader.load("bad-2", NeighborhoodTestData.text(duplicateCodes)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> loader.load("bad-3", NeighborhoodTestData.text(withoutPoint)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(neighborhoodApi.findNeighborhood(N2).orElseThrow().status()).isEqualTo(NeighborhoodStatus.VALID);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM nbr_dataset_version WHERE version LIKE 'bad-%'",
				Integer.class)).isZero();
	}

	/** legacy 04-#26: 동네는 행정동 코드로 식별되고 유일하다. 동시 조회·동시 적재에도 중복 동네가 생기지 않는다. */
	@Test
	void legacy04_26_concurrentReadsAndLoadsNeverDuplicateNeighborhoods() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(8);
		try {
			List<Callable<Object>> calls = new ArrayList<>();
			for (int i = 0; i < 16; i++) {
				calls.add(() -> neighborhoodApi.findNeighborhood(N1).orElseThrow().id());
			}
			for (int i = 0; i < 3; i++) {
				calls.add(() -> NeighborhoodTestData.loadBase(loader));
			}
			for (Future<Object> f : pool.invokeAll(calls)) {
				f.get();
			}
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM nbr_neighborhood WHERE status = 'VALID' AND code LIKE '99000000%'",
				Integer.class)).isEqualTo(7);
		assertThat(jdbc.queryForObject("SELECT count(DISTINCT code) FROM nbr_neighborhood", Integer.class))
			.isEqualTo(jdbc.queryForObject("SELECT count(*) FROM nbr_neighborhood", Integer.class));
	}

}
