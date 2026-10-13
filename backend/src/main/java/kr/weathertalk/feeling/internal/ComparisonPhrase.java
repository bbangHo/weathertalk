package kr.weathertalk.feeling.internal;

import kr.weathertalk.feeling.TemperatureTag;

/**
 * 어제와 비교 문구 (BR-FEL-16). 방향은 오늘 단계가 중립(딱 좋음)의 어느 쪽인지로 고르고, 차이가
 * feeling.comparison.large-diff 이상이면 "훨씬", 아니면 "조금". 문구 최종안은 home·notification과 조율한다.
 */
final class ComparisonPhrase {

	private ComparisonPhrase() {
	}

	static String of(TemperatureTag today, TemperatureTag yesterday, int largeDiff) {
		int a = today.level();
		int d = a - yesterday.level();
		if (d == 0) {
			return "어제와 비슷해요";
		}
		String degree = Math.abs(d) >= largeDiff ? "훨씬" : "조금";
		int neutral = TemperatureTag.NEUTRAL.level();
		String direction;
		if (d < 0) {
			direction = a >= neutral ? "덜 더워요" : "더 추워요";
		}
		else {
			direction = a <= neutral ? "덜 추워요" : "더 더워요";
		}
		return "어제보다 " + degree + " " + direction;
	}

}
