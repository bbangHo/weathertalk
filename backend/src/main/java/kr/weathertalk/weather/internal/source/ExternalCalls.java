package kr.weathertalk.weather.internal.source;

import java.time.Duration;
import java.util.function.Supplier;

import org.springframework.web.client.RestClientException;

import kr.weathertalk.weather.internal.config.WeatherProperties;

/**
 * 외부 호출 재시도 (BR-WEA-06): 최대 external.max-attempts회, 시도 간 external.retry-backoff부터 두 배씩 대기.
 * 재시도는 같은 인자(같은 발표 시각)로만 한다. 발표분 미제공·키 미설정은 재시도하지 않는다.
 */
public class ExternalCalls {

	private final WeatherProperties.External rule;

	public ExternalCalls(WeatherProperties.External rule) {
		this.rule = rule;
	}

	public <T> T call(String description, Supplier<T> call) {
		RuntimeException last = null;
		for (int attempt = 1; attempt <= rule.maxAttempts(); attempt++) {
			try {
				return call.get();
			}
			catch (SourceException.NotReady | SourceException.KeyMissing e) {
				throw e;
			}
			catch (SourceException.Failure | RestClientException e) {
				last = e;
			}
			if (attempt < rule.maxAttempts()) {
				sleep(rule.retryBackoff().multipliedBy(1L << (attempt - 1)));
			}
		}
		throw last instanceof SourceException.Failure failure ? failure
				: new SourceException.Failure(description + " 호출 실패", last);
	}

	private static void sleep(Duration duration) {
		try {
			Thread.sleep(duration.toMillis());
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new SourceException.Failure("재시도 대기 중 중단됨", e);
		}
	}

}
