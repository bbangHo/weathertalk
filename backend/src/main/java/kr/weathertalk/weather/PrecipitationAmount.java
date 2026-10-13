package kr.weathertalk.weather;

import java.math.BigDecimal;

/**
 * 강수량·신적설 구간 (BR-WEA-11). 기상청 범주("1.0mm 미만", "30.0~50.0mm", "50.0mm 이상")를 수치로 바꾸지 않고 구간 그대로 둔다.
 *
 * @param lower 하한. "1mm 미만"은 0이며 {@code lowerExclusive}가 참(0 초과)
 * @param upper 상한. 없으면 상한 없음("이상")
 * @param unit mm(강수량) 또는 cm(신적설)
 * @param label 표시 문구("1mm 미만", "6.2mm", "30~50mm", "50mm 이상", "없음")
 */
public record PrecipitationAmount(double lower, Double upper, boolean lowerExclusive, String unit, String label) {

	public static PrecipitationAmount none(String unit) {
		return new PrecipitationAmount(0, 0.0, false, unit, "없음");
	}

	public static PrecipitationAmount lessThan(double upper, String unit) {
		return new PrecipitationAmount(0, upper, true, unit, format(upper) + unit + " 미만");
	}

	public static PrecipitationAmount exactly(double value, String unit) {
		if (value == 0) {
			return none(unit);
		}
		return new PrecipitationAmount(value, value, false, unit, format(value) + unit);
	}

	public static PrecipitationAmount between(double lower, double upper, String unit) {
		return new PrecipitationAmount(lower, upper, false, unit, format(lower) + "~" + format(upper) + unit);
	}

	public static PrecipitationAmount atLeast(double lower, String unit) {
		return new PrecipitationAmount(lower, null, false, unit, format(lower) + unit + " 이상");
	}

	/** 저장된 구간에서 다시 만든다(표시 문구 재계산). */
	public static PrecipitationAmount of(double lower, Double upper, boolean lowerExclusive, String unit) {
		if (lowerExclusive) {
			return lessThan(upper, unit);
		}
		if (upper == null) {
			return atLeast(lower, unit);
		}
		if (lower == upper) {
			return exactly(lower, unit);
		}
		return between(lower, upper, unit);
	}

	/** 양이 있는가("1mm 미만"은 0 초과로 본다). */
	public boolean isPositive() {
		return lower > 0 || lowerExclusive;
	}

	private static String format(double value) {
		return new BigDecimal(Double.toString(value)).stripTrailingZeros().toPlainString();
	}

}
