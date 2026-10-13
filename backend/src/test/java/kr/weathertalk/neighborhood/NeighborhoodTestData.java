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
 * 테스트용 SAMPLE 동네 데이터 (src/test/resources/neighborhood, 가상 사각형 7개 — 실제 행정동 아님).
 *
 * <pre>
 *  위도 37.520 ┌───────┬───────┬───────┐
 *              │ 3(다른)│ 4(다른)│ 6(다른)│
 *  위도 37.510 ├───────┼───────┼───────┤
 *              │ 1(가상)│ 2(가상)│ 5(가상)│
 *  위도 37.500 └───────┴───────┴───────┘
 *          경도 127.000  127.010  127.020  127.030
 *  7: 샘플도 먼군 바닷가동 (129.00~129.01, 35.10~35.11), CSV에 예보 격자(98, 76) 명시
 * </pre>
 */
public final class NeighborhoodTestData {

	public static final String BASE_VERSION = "SAMPLE-test-1";

	public static final NeighborhoodId N1 = new NeighborhoodId("9900000001");
	public static final NeighborhoodId N2 = new NeighborhoodId("9900000002");
	public static final NeighborhoodId N3 = new NeighborhoodId("9900000003");
	public static final NeighborhoodId N4 = new NeighborhoodId("9900000004");
	public static final NeighborhoodId N5 = new NeighborhoodId("9900000005");
	public static final NeighborhoodId N6 = new NeighborhoodId("9900000006");
	public static final NeighborhoodId N7 = new NeighborhoodId("9900000007");

	/** 동네 1 안쪽 */
	public static final DeviceLocation INSIDE_N1 = location(37.505, 127.005, 20.0);

	/** 동네 1·2의 공유 경계선 위 */
	public static final DeviceLocation ON_SHARED_EDGE_N1_N2 = location(37.505, 127.010, 20.0);

	/** 동네 2의 바깥 경계선(동쪽 없음, 남쪽 변) 위 */
	public static final DeviceLocation ON_OUTER_EDGE_N2 = location(37.500, 127.015, 20.0);

	/** 어느 동네에도 속하지 않는 바다 위 */
	public static final DeviceLocation AT_SEA = location(37.0, 125.5, 20.0);

	private NeighborhoodTestData() {
	}

	public static DeviceLocation location(double lat, double lon, Double accuracy) {
		return new DeviceLocation(lat, lon, accuracy);
	}

	/** 기준 SAMPLE 데이터를 적재한다(멱등). 다른 버전으로 바꾼 테스트도 이걸로 되돌린다. */
	public static DatasetLoadReport loadBase(NeighborhoodDatasetLoader loader) {
		return loader.load(BASE_VERSION, resource("neighborhood/SAMPLE-test-codes.csv"),
				resource("neighborhood/SAMPLE-test-boundaries.geojson"));
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
