package kr.weathertalk.weather;

/**
 * 에어코리아 대기질 등급(1 좋음 ~ 4 매우나쁨). 자외선 등급({@link UvGrade})과 다른 체계이며 섞어 표기하지 않는다.
 */
public enum AirGrade {

	GOOD("좋음"),

	MODERATE("보통"),

	BAD("나쁨"),

	VERY_BAD("매우나쁨");

	private final String label;

	AirGrade(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

	/** 둘 중 나쁜 쪽. 하나가 없으면 다른 쪽(통합 미세먼지 등급, 03 §3.6). */
	public static AirGrade worse(AirGrade a, AirGrade b) {
		if (a == null) {
			return b;
		}
		if (b == null) {
			return a;
		}
		return a.compareTo(b) >= 0 ? a : b;
	}

}
