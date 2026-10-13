package kr.weathertalk.weather;

/**
 * 신선도 (03 §2): 수집 결과를 지금 써도 되는지. 발표·관측·측정 시각으로만 판정하며 조회가 유효 기간을 늘리지 않는다(BR-WEA-03~05).
 */
public enum Freshness {

	/** 받을 수 있는 최신 발표분이다. */
	FRESH,

	/** 최신은 아니지만 아직 쓸 수 있다(외부 실패·제공 지연 중 마지막 정상값). */
	DELAYED,

	/** 쓸 수 없다. 값은 '현재'로 보여주지 않는다. */
	NONE;

	/** 둘 중 나쁜 쪽. */
	public Freshness worse(Freshness other) {
		return rank() >= other.rank() ? this : other;
	}

	private int rank() {
		return switch (this) {
			case FRESH -> 0;
			case DELAYED -> 1;
			case NONE -> 2;
		};
	}

}
