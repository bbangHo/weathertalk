package kr.weathertalk.weather;

/**
 * 자외선 지수 등급: 기상청 공식 5단계와 생활기상지수 안내 문구 (03 §3.6, legacy 04-#14).
 * 경계는 기상청 공식 정의라 운영 파라미터가 아니다.
 */
public enum UvGrade {

	LOW("낮음", 0, "햇볕 노출에 대한 보호조치가 필요하지 않음. 그러나 햇볕에 민감한 피부를 가진 분은 자외선 차단제를 발라야 함"),

	MODERATE("보통", 3, "2~3시간 내에도 햇볕에 노출 시에 피부 화상을 입을 수 있음. 모자, 선글라스 이용, 자외선 차단제를 발라야 함"),

	HIGH("높음", 6, "햇볕에 노출 시 1~2시간 내에도 피부 화상을 입을 수 있어 위험함. 한낮에는 그늘에 머물러야 함. 외출 시 긴 소매 옷, 모자, 선글라스 이용, 자외선 차단제를 정기적으로 발라야 함"),

	VERY_HIGH("매우높음", 8, "햇볕에 노출 시 수십 분 이내에도 피부 화상을 입을 수 있어 매우 위험함. 오전 10시부터 오후 3시까지 외출을 피하고 실내나 그늘에 머물러야 함. 외출 시 긴 소매 옷, 모자, 선글라스 이용, 자외선 차단제를 정기적으로 발라야 함"),

	EXTREME("위험", 11, "햇볕에 노출 시 수십 분 이내에도 피부 화상을 입을 수 있어 가장 위험함. 가능한 실내에 머물러야 함. 외출 시 긴 소매 옷, 모자, 선글라스 이용, 자외선 차단제를 정기적으로 발라야 함");

	/** 값이 없을 때 표시 문구. 대기질의 '좋음' 등으로 대신하지 않는다. */
	public static final String NO_DATA_LABEL = "정보 없음";

	private final String label;
	private final int minIndex;
	private final String guidance;

	UvGrade(String label, int minIndex, String guidance) {
		this.label = label;
		this.minIndex = minIndex;
		this.guidance = guidance;
	}

	public String label() {
		return label;
	}

	public String guidance() {
		return guidance;
	}

	/** @return 지수가 없거나 음수면 null */
	public static UvGrade of(Integer index) {
		if (index == null || index < 0) {
			return null;
		}
		UvGrade result = LOW;
		for (UvGrade grade : values()) {
			if (index >= grade.minIndex) {
				result = grade;
			}
		}
		return result;
	}

}
