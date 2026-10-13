package kr.weathertalk.weather.internal.conversion;

import java.time.Month;
import java.util.Optional;

import kr.weathertalk.weather.internal.config.WeatherProperties;

/**
 * 체감온도 (BR-WEA-12, 기상청 산출식). 겨울 공식은 풍속 '이상' 조건, 여름 공식은 적용 월 조건을 따른다(legacy 04-#15).
 * 판정에 필요한 입력이 없으면 없음 — 기온으로 대체하지 않는다.
 */
public class FeelsLikeCalculator {

	private final WeatherProperties.FeelsLike rule;

	public FeelsLikeCalculator(WeatherProperties.FeelsLike rule) {
		this.rule = rule;
	}

	/**
	 * @param temperature 기온 ℃
	 * @param humidity 상대습도 %
	 * @param windSpeed 풍속 m/s
	 * @param month 여름 공식 적용 월 판정용
	 */
	public Optional<Double> calculate(Double temperature, Double humidity, Double windSpeed, Month month) {
		if (temperature == null) {
			return Optional.empty();
		}
		double ta = temperature;
		if (ta <= rule.winterMaxTemp()) {
			if (windSpeed == null) {
				return Optional.empty();
			}
			if (windSpeed >= rule.winterMinWind()) {
				return Optional.of(windChill(ta, windSpeed));
			}
		}
		boolean summer = month != null && rule.summerMonths().contains(month.getValue())
				&& ta >= rule.summerMinTemp();
		if (summer) {
			if (humidity == null) {
				return Optional.empty();
			}
			return Optional.of(summerFeelsLike(ta, humidity));
		}
		return Optional.of(ta);
	}

	static double windChill(double ta, double windSpeed) {
		double v = Math.pow(windSpeed * 3.6, 0.16);
		return 13.12 + 0.6215 * ta - 11.37 * v + 0.3965 * v * ta;
	}

	static double summerFeelsLike(double ta, double rh) {
		double tw = ta * Math.atan(0.151977 * Math.sqrt(rh + 8.313659)) + Math.atan(ta + rh) - Math.atan(rh - 1.67633)
				+ 0.00391838 * Math.pow(rh, 1.5) * Math.atan(0.023101 * rh) - 4.686035;
		return -0.2442 + 0.55399 * tw + 0.45535 * ta - 0.0022 * tw * tw + 0.00278 * tw * ta + 3.0;
	}

}
