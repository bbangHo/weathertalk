package kr.weathertalk.feeling;

/** 바람 체감 태그 3단계 (04 §3.1, 선택, 한쪽 방향 강도 척도, 중립 = 1). 현행의 강도 역전·4단계 검증 실패 재발 방지(legacy 03-#4, 03-#13). */
public enum WindTag implements FeelingTag {

	CALM(1, "잔잔해요"),

	LIGHT(2, "조금 불어요"),

	STRONG(3, "세게 불어요");

	public static final WindTag NEUTRAL = CALM;

	private final int level;
	private final String label;

	WindTag(int level, String label) {
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
	public static WindTag of(int level) {
		for (WindTag tag : values()) {
			if (tag.level == level) {
				return tag;
			}
		}
		throw new IllegalArgumentException("바람 체감 태그 단계는 1~3입니다: " + level);
	}

}
