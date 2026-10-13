package kr.weathertalk.feeling;

/**
 * 온도 체감 태그 7단계 (04 §3.1, 필수, 중립 = 4). 피드백 한 단계 변환(BR-FEL-06)과 옷 추천이 이 순서에 의존한다.
 */
public enum TemperatureTag implements FeelingTag {

	VERY_COLD(1, "매우 추움"),

	COLD(2, "추움"),

	SLIGHTLY_COLD(3, "조금 추움"),

	JUST_RIGHT(4, "딱 좋음"),

	SLIGHTLY_HOT(5, "조금 더움"),

	HOT(6, "더움"),

	VERY_HOT(7, "매우 더움");

	public static final TemperatureTag NEUTRAL = JUST_RIGHT;

	private final int level;
	private final String label;

	TemperatureTag(int level, String label) {
		this.level = level;
		this.label = label;
	}

	@Override
	public int level() {
		return level;
	}

	@Override
	public String label() {
		return label;
	}

	/** @throws IllegalArgumentException 범주에 없는 단계 */
	public static TemperatureTag of(int level) {
		for (TemperatureTag tag : values()) {
			if (tag.level == level) {
				return tag;
			}
		}
		throw new IllegalArgumentException("온도 체감 태그 단계는 1~7입니다: " + level);
	}

	/** 단계를 delta만큼 옮기되 양 끝(1, 7)을 넘지 않는다(BR-FEL-06). */
	public TemperatureTag shift(int delta) {
		return of(Math.max(VERY_COLD.level, Math.min(VERY_HOT.level, level + delta)));
	}

}
