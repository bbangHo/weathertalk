package kr.weathertalk.home;

/**
 * 홈 섹션 하나와 그 상태 (07 §1.4). 앱은 데이터 없음과 실패를 다르게 보여준다.
 *
 * @param data 정상·데이터 없음일 때 모듈 응답 그대로(home은 값을 바꾸지 않는다). 실패면 null
 * @param failure 실패일 때만 원인
 */
public record HomeSection<T>(Status status, T data, Failure failure) {

	public enum Status {

		OK,

		/** 모듈이 정상 응답했으나 보여줄 값이 없음 */
		EMPTY,

		/** 오류·시간 초과(home.section-timeout) */
		FAILED

	}

	public enum Failure {

		TIMEOUT,

		ERROR,

		/** 의존하는 선행 섹션이 실패해 조회하지 않음(07 §1.4-3) */
		PREREQUISITE_FAILED

	}

	public static <T> HomeSection<T> ok(T data) {
		return new HomeSection<>(Status.OK, data, null);
	}

	public static <T> HomeSection<T> empty(T data) {
		return new HomeSection<>(Status.EMPTY, data, null);
	}

	public static <T> HomeSection<T> failed(Failure failure) {
		return new HomeSection<>(Status.FAILED, null, failure);
	}

}
