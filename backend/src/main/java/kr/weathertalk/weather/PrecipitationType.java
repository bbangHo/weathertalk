package kr.weathertalk.weather;

/**
 * 강수 형태 (BR-WEA-10). 기상청이 정의하지 않은 코드는 이 값이 아니라 '알 수 없음'(null)으로 둔다.
 */
public enum PrecipitationType {

	NONE("없음"),

	RAIN("비"),

	RAIN_SNOW("비·눈"),

	SNOW("눈"),

	SHOWER("소나기");

	private final String label;

	PrecipitationType(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

	public boolean isPrecipitating() {
		return this != NONE;
	}

}
