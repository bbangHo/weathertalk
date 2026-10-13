package kr.weathertalk.feeling;

/**
 * 체감 태그 한 단계 (04 §3.1). 세 범주 모두 단계 번호가 곧 순서이며(작을수록 춥다/건조하다/잔잔하다), 입력 검증·정렬·집계는
 * 이 단계 번호 하나의 정의만 쓴다(legacy 03-#4, 03-#16).
 */
public interface FeelingTag {

	int level();

	String label();

}
