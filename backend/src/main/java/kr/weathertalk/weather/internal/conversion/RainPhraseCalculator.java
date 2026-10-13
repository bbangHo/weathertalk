package kr.weathertalk.weather.internal.conversion;

import kr.weathertalk.weather.PrecipitationAmount;
import kr.weathertalk.weather.PrecipitationType;
import kr.weathertalk.weather.internal.config.WeatherProperties;

/**
 * 강수 문구 (BR-WEA-13, 기상청 강도 구분). 경계는 모두 '이상'이며 구간 하한으로 비교한다("1mm 미만"은 0 초과).
 * 강수 형태가 있는데 양이 0·결측이면 최소 '약한'으로 표시한다.
 */
public class RainPhraseCalculator {

	public static final String NO_PRECIPITATION = "강수 없음";

	private final WeatherProperties.RainPhrase rule;

	public RainPhraseCalculator(WeatherProperties.RainPhrase rule) {
		this.rule = rule;
	}

	/**
	 * @param rain 1시간 강수량 구간(mm)
	 * @param snow 1시간 신적설 구간(cm)
	 * @return 강수 형태를 알 수 없으면 null(문구 없음)
	 */
	public String phrase(PrecipitationType type, PrecipitationAmount rain, PrecipitationAmount snow) {
		if (type == null) {
			return null;
		}
		return switch (type) {
			case NONE -> NO_PRECIPITATION;
			case SHOWER -> "소나기";
			case RAIN -> rainIntensity(rain) + " 비";
			case RAIN_SNOW -> rainIntensity(rain) + " 비·눈";
			case SNOW -> snowIntensity(snow) + " 눈";
		};
	}

	private String rainIntensity(PrecipitationAmount amount) {
		double value = threshold(amount);
		if (value >= rule.rainExtreme()) {
			return "매우 강한";
		}
		if (value >= rule.rainHeavy()) {
			return "강한";
		}
		if (value >= rule.rainModerate()) {
			return "보통";
		}
		return "약한";
	}

	private String snowIntensity(PrecipitationAmount amount) {
		double value = threshold(amount);
		if (value >= rule.snowHeavy()) {
			return "강한";
		}
		if (value >= rule.snowModerate()) {
			return "보통";
		}
		return "약한";
	}

	private static double threshold(PrecipitationAmount amount) {
		return amount == null ? 0 : amount.lower();
	}

}
