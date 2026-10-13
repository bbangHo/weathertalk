package kr.weathertalk.neighborhood;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.ClassPathResource;

import kr.weathertalk.neighborhood.internal.dataset.DatasetLoadReport;
import kr.weathertalk.neighborhood.internal.dataset.NeighborhoodDatasetLoader;
import kr.weathertalk.shared.Ids.NeighborhoodId;

/**
 * 테스트용 SAMPLE 동네 데이터 (src/test/resources/neighborhood, 가상 대표점 7개 — 실제 행정동 아님, 경계 없음).
 * 기상청 자료처럼 대표점만 있으므로 판정은 가장 가까운 대표점이다(ADR 0016).
 *
 * <pre>
 *  위도 37.515   3(다른)    4(다른)    6(다른)
 *  위도 37.505   1(가상)    2(가상)    5(가상)
 *          경도 127.005  127.015  127.025      (이웃 대표점 간격 약 0.9~1.1km)
 *  7: 샘플도 먼군 바닷가동 (35.105, 129.005), CSV에 예보 격자(98, 76) 명시 — 계산식 결과와 달라 격자 검증 경고 대상
 * </pre>
 */
public final class NeighborhoodTestData {

	public static final String BASE_VERSION = "SAMPLE-test-1";

	public static final String CODES = "neighborhood/SAMPLE-test-codes.csv";

	public static final NeighborhoodId N1 = new NeighborhoodId("9900000001");
	public static final NeighborhoodId N2 = new NeighborhoodId("9900000002");
	public static final NeighborhoodId N3 = new NeighborhoodId("9900000003");
	public static final NeighborhoodId N4 = new NeighborhoodId("9900000004");
	public static final NeighborhoodId N5 = new NeighborhoodId("9900000005");
	public static final NeighborhoodId N6 = new NeighborhoodId("9900000006");
	public static final NeighborhoodId N7 = new NeighborhoodId("9900000007");

	/** 동네 1 대표점 바로 옆 */
	public static final DeviceLocation INSIDE_N1 = location(37.506, 127.004, 20.0);

	/** 동네 1·2 대표점에서 같은 거리 */
	public static final DeviceLocation EQUIDISTANT_N1_N2 = location(37.505, 127.010, 20.0);

	/** 동네 2 대표점 근처 */
	public static final DeviceLocation NEAR_N2 = location(37.504, 127.016, 20.0);

	/** 가장 가까운 대표점도 최대 거리 밖인 바다 위 */
	public static final DeviceLocation AT_SEA = location(37.0, 125.5, 20.0);

	private NeighborhoodTestData() {
	}

	public static DeviceLocation location(double lat, double lon, Double accuracy) {
		return new DeviceLocation(lat, lon, accuracy);
	}

	/** 기준 SAMPLE 데이터를 적재한다(멱등). 다른 버전으로 바꾼 테스트도 이걸로 되돌린다. */
	public static DatasetLoadReport loadBase(NeighborhoodDatasetLoader loader) {
		return loader.load(BASE_VERSION, resource(CODES));
	}

	public static Reader resource(String path) {
		try {
			return new InputStreamReader(new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public static Reader text(String content) {
		return new StringReader(content);
	}

}
