package kr.weathertalk.weather;

/** 현장 제보 축 (03 §3.7). 한 회원은 동네·축마다 최신 제보 1건으로 센다. */
public enum FieldReportAxis {

	SKY_PRECIPITATION("하늘·강수"),

	AIR("대기");

	private final String label;

	FieldReportAxis(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

}
