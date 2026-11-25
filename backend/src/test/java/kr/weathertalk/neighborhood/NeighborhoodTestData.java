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
 * 좌표 → 동네 판정은 앱이 기기 안에서 하므로(ADR 0009) 서버 테스트는 동네 ID로 현재 동네를 확정한다.
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

	/** 앱이 동네 1로 판정한 위치(쓰기 요청의 동네 ID) */
	public static final NeighborhoodId INSIDE_N1 = N1;

	/** 앱이 동네 2로 판정한 위치 */
	public static final NeighborhoodId NEAR_N2 = N2;

	/** 동네 기준 데이터에 없는 동네 ID */
	public static final NeighborhoodId UNKNOWN = new NeighborhoodId("9999999999");

	private NeighborhoodTestData() {
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
