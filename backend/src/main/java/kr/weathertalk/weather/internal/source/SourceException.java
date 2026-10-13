package kr.weathertalk.weather.internal.source;

/**
 * 외부 수집 실패 (BR-WEA-06). 결과 코드 이상·형식 오류·시간 초과는 원천과 관계없이 {@link Failure},
 * 발표분이 아직 없으면 {@link NotReady}, 인증키 미설정은 {@link KeyMissing}으로 구분한다.
 */
public abstract sealed class SourceException extends RuntimeException
		permits SourceException.Failure, SourceException.NotReady, SourceException.KeyMissing {

	protected SourceException(String message, Throwable cause) {
		super(message, cause);
	}

	/** 재시도 대상 실패. */
	public static final class Failure extends SourceException {

		public Failure(String message) {
			super(message, null);
		}

		public Failure(String message, Throwable cause) {
			super(message, cause);
		}

	}

	/** 요청한 발표분이 아직 제공되지 않았다(빈 응답·자료 없음). 즉시 재시도하지 않는다. */
	public static final class NotReady extends SourceException {

		public NotReady(String message) {
			super(message, null);
		}

	}

	/** 인증키가 설정되지 않아 호출하지 않았다. */
	public static final class KeyMissing extends SourceException {

		public KeyMissing(String source) {
			super(source + " 인증키가 설정되지 않아 수집하지 않습니다.", null);
		}

	}

}
