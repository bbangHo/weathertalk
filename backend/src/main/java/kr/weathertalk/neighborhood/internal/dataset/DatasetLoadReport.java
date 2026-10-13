package kr.weathertalk.neighborhood.internal.dataset;

import java.util.List;

/**
 * 동네 기준 데이터 적재 비교 보고서 (02 §3.2 갱신: 코드·이름 변경, 신설, 폐지).
 *
 * @param changed 이름·경계·예보 격자가 바뀌었거나 폐지됐다가 다시 유효해진 동네
 */
public record DatasetLoadReport(String version, int total, List<String> added, List<String> changed,
		List<String> retired) {

	public boolean noChanges() {
		return added.isEmpty() && changed.isEmpty() && retired.isEmpty();
	}

	public String summary() {
		return "version=" + version + " total=" + total + " added=" + added.size() + " changed=" + changed.size()
				+ " retired=" + retired.size();
	}

}
