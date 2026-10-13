package kr.weathertalk.weather.internal.conversion;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import kr.weathertalk.weather.PrecipitationAmount;
import kr.weathertalk.weather.PrecipitationType;
import kr.weathertalk.weather.SkyCondition;

/**
 * 기상청 코드값 → 도메인 값 변환 (BR-WEA-09~11). 정의되지 않은 코드·결측은 null(없음)로 둔다.
 */
public final class KmaCodes {

	/** 기상청 결측 표기(±900 이상, 예: -999, 999.9). */
	private static final double MISSING_ABS = 900;

	private static final Pattern NUMBER = Pattern.compile("(-?\\d+(?:\\.\\d+)?)");
	private static final Pattern RANGE = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*~\\s*(\\d+(?:\\.\\d+)?)");

	private KmaCodes() {
	}

	/** BR-WEA-09: 1 맑음, 3 구름많음, 4 흐림. 그 외(2 포함)는 없음 — 맑음으로 간주하지 않는다(legacy 04-#5). */
	public static SkyCondition sky(String code) {
		Integer value = integer(code);
		if (value == null) {
			return null;
		}
		return switch (value) {
			case 1 -> SkyCondition.CLEAR;
			case 3 -> SkyCondition.MOSTLY_CLOUDY;
			case 4 -> SkyCondition.OVERCAST;
			default -> null;
		};
	}

	/** BR-WEA-10 단기예보 PTY: 0 없음, 1 비, 2 비·눈, 3 눈, 4 소나기. */
	public static PrecipitationType shortTermPrecipitationType(String code) {
		Integer value = integer(code);
		if (value == null) {
			return null;
		}
		return switch (value) {
			case 0 -> PrecipitationType.NONE;
			case 1 -> PrecipitationType.RAIN;
			case 2 -> PrecipitationType.RAIN_SNOW;
			case 3 -> PrecipitationType.SNOW;
			case 4 -> PrecipitationType.SHOWER;
			default -> null;
		};
	}

	/** BR-WEA-10 실황 PTY: 0 없음, 1 비, 2 비·눈, 3 눈, 5 빗방울 → 비, 6 빗방울눈날림 → 비·눈, 7 눈날림 → 눈. */
	public static PrecipitationType observationPrecipitationType(String code) {
		Integer value = integer(code);
		if (value == null) {
			return null;
		}
		return switch (value) {
			case 0 -> PrecipitationType.NONE;
			case 1, 5 -> PrecipitationType.RAIN;
			case 2, 6 -> PrecipitationType.RAIN_SNOW;
			case 3, 7 -> PrecipitationType.SNOW;
			default -> null;
		};
	}

	/** 중기 육상예보 문구(wf: "맑음", "구름많고 비", "흐리고 비/눈" 등)의 하늘 상태. */
	public static SkyCondition midTermSky(String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		String t = text.strip();
		if (t.startsWith("맑")) {
			return SkyCondition.CLEAR;
		}
		if (t.startsWith("구름많")) {
			return SkyCondition.MOSTLY_CLOUDY;
		}
		if (t.startsWith("흐")) {
			return SkyCondition.OVERCAST;
		}
		return null;
	}

	/** 중기 육상예보 문구의 강수 형태(03 §3.5 "중기는 예보 문구에서 변환"). */
	public static PrecipitationType midTermPrecipitationType(String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		if (text.contains("비/눈") || text.contains("눈/비")) {
			return PrecipitationType.RAIN_SNOW;
		}
		if (text.contains("소나기")) {
			return PrecipitationType.SHOWER;
		}
		if (text.contains("비")) {
			return PrecipitationType.RAIN;
		}
		if (text.contains("눈")) {
			return PrecipitationType.SNOW;
		}
		return PrecipitationType.NONE;
	}

	/**
	 * BR-WEA-11: 강수량(PCP·RN1)·신적설(SNO) 범주 문자열 → 구간. "1.0mm 미만"을 1.0으로 바꾸지 않는다(legacy 04 §3 본문).
	 *
	 * @param unit "mm" 또는 "cm"
	 * @return 결측·해석 불가면 null
	 */
	public static PrecipitationAmount amount(String raw, String unit) {
		if (raw == null) {
			return null;
		}
		String text = raw.strip();
		if (text.isEmpty() || text.equals("-")) {
			return null;
		}
		if (text.contains("없음")) {
			return PrecipitationAmount.none(unit);
		}
		Matcher range = RANGE.matcher(text);
		if (range.find()) {
			return PrecipitationAmount.between(Double.parseDouble(range.group(1)), Double.parseDouble(range.group(2)),
					unit);
		}
		Matcher number = NUMBER.matcher(text);
		if (!number.find()) {
			return null;
		}
		double value = Double.parseDouble(number.group(1));
		if (value < 0 || value >= MISSING_ABS) {
			return null;
		}
		if (text.contains("미만")) {
			return PrecipitationAmount.lessThan(value, unit);
		}
		if (text.contains("이상")) {
			return PrecipitationAmount.atLeast(value, unit);
		}
		return PrecipitationAmount.exactly(value, unit);
	}

	/** 수치 항목. 결측 표기(±900 이상)·빈 값·형식 오류는 null. */
	public static Double number(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			double value = Double.parseDouble(raw.strip());
			return Math.abs(value) >= MISSING_ABS || Double.isNaN(value) ? null : value;
		}
		catch (NumberFormatException e) {
			return null;
		}
	}

	public static Integer integer(String raw) {
		Double value = number(raw);
		return value == null ? null : (int) Math.round(value);
	}

}
