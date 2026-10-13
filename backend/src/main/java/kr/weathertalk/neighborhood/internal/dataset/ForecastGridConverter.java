package kr.weathertalk.neighborhood.internal.dataset;

import kr.weathertalk.neighborhood.ForecastGrid;

/**
 * 위경도(WGS84) → 기상청 단기예보 격자 (BR-NBR-04, 레거시 spec/04 BR-LOC-05 상수 유지).
 * <p>
 * 람베르트 정각원추도법: 지구반경 6371.00877km, 격자간격 5km, 표준위도 30°·60°, 기준점 (126°E, 38°N) = 격자 (43, 136).
 * 기상청 활용 가이드의 C 코드(XO=42, YO=135, 결과 = 투영값 + 1.5 내림)와 같다. 레거시와 달리 배정밀도로 계산한다.
 * 기준 데이터 적재 때 기상청 행정구역별 격자 표에 없는 동네의 대표 좌표에만 쓴다(표 값 우선).
 */
public final class ForecastGridConverter {

	private static final double EARTH_RADIUS_KM = 6371.00877;
	private static final double GRID_KM = 5.0;
	private static final double STANDARD_LAT_1 = 30.0;
	private static final double STANDARD_LAT_2 = 60.0;
	private static final double ORIGIN_LON = 126.0;
	private static final double ORIGIN_LAT = 38.0;
	private static final double ORIGIN_X = 42.0; // 210 / 5
	private static final double ORIGIN_Y = 135.0; // 675 / 5

	private static final double RADIAN = Math.PI / 180.0;
	private static final double RE = EARTH_RADIUS_KM / GRID_KM;
	private static final double SN;
	private static final double SF;
	private static final double RO;

	static {
		double slat1 = STANDARD_LAT_1 * RADIAN;
		double slat2 = STANDARD_LAT_2 * RADIAN;
		double olat = ORIGIN_LAT * RADIAN;
		double sn = Math.tan(Math.PI * 0.25 + slat2 * 0.5) / Math.tan(Math.PI * 0.25 + slat1 * 0.5);
		SN = Math.log(Math.cos(slat1) / Math.cos(slat2)) / Math.log(sn);
		double sf = Math.tan(Math.PI * 0.25 + slat1 * 0.5);
		SF = Math.pow(sf, SN) * Math.cos(slat1) / SN;
		double ro = Math.tan(Math.PI * 0.25 + olat * 0.5);
		RO = RE * SF / Math.pow(ro, SN);
	}

	private ForecastGridConverter() {
	}

	public static ForecastGrid toGrid(double latitude, double longitude) {
		double ra = RE * SF / Math.pow(Math.tan(Math.PI * 0.25 + latitude * RADIAN * 0.5), SN);
		double theta = longitude * RADIAN - ORIGIN_LON * RADIAN;
		if (theta > Math.PI) {
			theta -= 2.0 * Math.PI;
		}
		if (theta < -Math.PI) {
			theta += 2.0 * Math.PI;
		}
		theta *= SN;
		int nx = (int) Math.floor(ra * Math.sin(theta) + ORIGIN_X + 1.5);
		int ny = (int) Math.floor(RO - ra * Math.cos(theta) + ORIGIN_Y + 1.5);
		return new ForecastGrid(nx, ny);
	}

}
