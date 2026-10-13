package kr.weathertalk.weather;

/**
 * 자외선 지수 (03 §3.6). 값이 없으면 등급 없음, 표시 문구는 '정보 없음'.
 *
 * @param issuedAt 자외선 발표 시각
 * @param gradeLabel 기상청 공식 등급 이름 또는 '정보 없음'
 */
public record UvIndex(java.time.Instant issuedAt, Integer value, UvGrade grade, String gradeLabel, String guidance,
		Freshness freshness) {

	public static UvIndex of(java.time.Instant issuedAt, Integer value, Freshness freshness) {
		UvGrade grade = UvGrade.of(value);
		return new UvIndex(issuedAt, grade == null ? null : value, grade,
				grade == null ? UvGrade.NO_DATA_LABEL : grade.label(), grade == null ? null : grade.guidance(), freshness);
	}

	public static UvIndex none() {
		return of(null, null, Freshness.NONE);
	}

}
