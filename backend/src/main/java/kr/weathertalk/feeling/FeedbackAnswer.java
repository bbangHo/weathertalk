package kr.weathertalk.feeling;

/**
 * 체감 피드백 응답 (ADR 0011, BR-FEL-06). 보여준 온도 태그를 기준으로 한 단계 옮기며 양 끝은 그대로 둔다.
 * 응답 원문은 기록에 함께 남아 "끝 단계보다 더 극단적"인 응답을 구분할 수 있다.
 */
public enum FeedbackAnswer {

	/** 맞아요 */
	RIGHT(0),

	/** 더 추워요 */
	COLDER(-1),

	/** 더 더워요 */
	HOTTER(1);

	private final int delta;

	FeedbackAnswer(int delta) {
		this.delta = delta;
	}

	public TemperatureTag apply(TemperatureTag shown) {
		return shown.shift(delta);
	}

}
