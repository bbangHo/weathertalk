package kr.weathertalk.feeling;

/** 습도 체감 태그 5단계 (04 §3.1, 선택, 중립 = 3). */
public enum HumidityTag implements FeelingTag {

	VERY_DRY(1, "매우 건조해요"),

	DRY(2, "건조해요"),

	COMFORTABLE(3, "쾌적해요"),

	HUMID(4, "습해요"),

	VERY_HUMID(5, "매우 습해요");

	public static final HumidityTag NEUTRAL = COMFORTABLE;

	private final int level;
	private final String label;

	HumidityTag(int level, String label) {
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
	public static HumidityTag of(int level) {
		for (HumidityTag tag : values()) {
			if (tag.level == level) {
				return tag;
			}
		}
		throw new IllegalArgumentException("습도 체감 태그 단계는 1~5입니다: " + level);
	}

}
