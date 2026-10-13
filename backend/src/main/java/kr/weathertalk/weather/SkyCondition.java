package kr.weathertalk.weather;

/**
 * 하늘 상태 (BR-WEA-09, 기상청 SKY 정의). 저장·응답 식별값은 이름이며 선언 순서에 의존하지 않는다.
 */
public enum SkyCondition {

	CLEAR("맑음"),

	MOSTLY_CLOUDY("구름많음"),

	OVERCAST("흐림");

	private final String label;

	SkyCondition(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

}
