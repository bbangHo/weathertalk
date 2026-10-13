package kr.weathertalk.weather;

/**
 * 현장 제보 종류 (03 §3.7, 확정 목록 — 안개·우박 등은 두지 않는다). 저장·응답 식별값은 이름이며 선언 순서에 의존하지 않는다.
 */
public enum FieldReportKind {

	RAIN("비", FieldReportAxis.SKY_PRECIPITATION, PrecipitationType.RAIN),

	SNOW("눈", FieldReportAxis.SKY_PRECIPITATION, PrecipitationType.SNOW),

	OVERCAST("흐림", FieldReportAxis.SKY_PRECIPITATION, null),

	CLEAR("맑음", FieldReportAxis.SKY_PRECIPITATION, null),

	DUST_BAD("미세먼지 나쁨", FieldReportAxis.AIR, null),

	AIR_CLEAN("공기 깨끗함", FieldReportAxis.AIR, null);

	private final String label;
	private final FieldReportAxis axis;
	private final PrecipitationType precipitationType;

	FieldReportKind(String label, FieldReportAxis axis, PrecipitationType precipitationType) {
		this.label = label;
		this.axis = axis;
		this.precipitationType = precipitationType;
	}

	public String label() {
		return label;
	}

	public FieldReportAxis axis() {
		return axis;
	}

	/** 비·눈이면 그 강수 형태(BR-WEA-17 판정 단위), 아니면 null. */
	public PrecipitationType precipitationType() {
		return precipitationType;
	}

}
