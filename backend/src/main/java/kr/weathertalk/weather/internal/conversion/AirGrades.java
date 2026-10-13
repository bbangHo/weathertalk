package kr.weathertalk.weather.internal.conversion;

import kr.weathertalk.weather.AirGrade;

/**
 * 대기질 등급. 에어코리아가 준 등급(1~4)을 그대로 쓰고, 등급이 결측이고 농도만 있으면
 * 오염물질별 공식 기준 경계로 정한다 — PM2.5에 PM10 경계를 쓰지 않는다(legacy 03-#15).
 * 경계는 환경부 공식 기준이라 운영 파라미터가 아니다.
 */
public final class AirGrades {

	/** PM10(㎍/㎥): 좋음 ~30, 보통 ~80, 나쁨 ~150, 매우나쁨 151~ */
	private static final double[] PM10_UPPER = { 30, 80, 150 };

	/** PM2.5(㎍/㎥): 좋음 ~15, 보통 ~35, 나쁨 ~75, 매우나쁨 76~ */
	private static final double[] PM25_UPPER = { 15, 35, 75 };

	private AirGrades() {
	}

	/** 에어코리아 등급 코드 1~4. 그 외는 null. */
	public static AirGrade fromCode(Integer code) {
		if (code == null) {
			return null;
		}
		return switch (code) {
			case 1 -> AirGrade.GOOD;
			case 2 -> AirGrade.MODERATE;
			case 3 -> AirGrade.BAD;
			case 4 -> AirGrade.VERY_BAD;
			default -> null;
		};
	}

	public static AirGrade pm10(Double value) {
		return byUpperBounds(value, PM10_UPPER);
	}

	public static AirGrade pm25(Double value) {
		return byUpperBounds(value, PM25_UPPER);
	}

	private static AirGrade byUpperBounds(Double value, double[] upper) {
		if (value == null || value < 0) {
			return null;
		}
		if (value <= upper[0]) {
			return AirGrade.GOOD;
		}
		if (value <= upper[1]) {
			return AirGrade.MODERATE;
		}
		if (value <= upper[2]) {
			return AirGrade.BAD;
		}
		return AirGrade.VERY_BAD;
	}

}
