package kr.weathertalk.home.internal;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import kr.weathertalk.home.HomeSection;

/**
 * 섹션별 독립 조회 (07 §1.4): 섹션마다 따로 실행하고 home.section-timeout을 적용한다. 오류·시간 초과는 그 섹션만 실패로 바꾸고
 * 다른 섹션과 화면 전체를 막지 않는다. 의존 섹션은 선행 섹션이 실패하면 조회하지 않는다.
 * <p>
 * 섹션 추가 방법: {@link #start}(독립) 또는 {@link #after}(선행 섹션 결과가 필요)로 시작하고 {@link kr.weathertalk.home.HomeView}에 필드를 더한다.
 */
public class SectionRunner implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(SectionRunner.class);

	private final Executor executor;
	private final Duration timeout;

	public SectionRunner(Executor executor, Duration timeout) {
		this.executor = executor;
		this.timeout = timeout;
	}

	/**
	 * @param isEmpty 모듈이 정상 응답했으나 보여줄 값이 없는가(섹션 상태 표시용. 값 판단은 소유 모듈이 이미 했다)
	 */
	public <T> CompletableFuture<HomeSection<T>> start(String name, Supplier<T> query, Predicate<T> isEmpty) {
		return CompletableFuture.supplyAsync(query, executor)
			.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
			.handle((value, error) -> toSection(name, value, error, isEmpty));
	}

	/** 선행 섹션 결과가 있어야 조회할 수 있는 섹션. 선행이 실패면 조회하지 않고 PREREQUISITE_FAILED. */
	public <P, T> CompletableFuture<HomeSection<T>> after(String name, CompletableFuture<HomeSection<P>> prerequisite,
			Function<P, T> query, Predicate<T> isEmpty) {
		return prerequisite.thenCompose(pre -> {
			if (pre.status() == HomeSection.Status.FAILED) {
				return CompletableFuture.completedFuture(HomeSection.<T>failed(HomeSection.Failure.PREREQUISITE_FAILED));
			}
			return start(name, () -> query.apply(pre.data()), isEmpty);
		});
	}

	@Override
	public void close() {
		if (executor instanceof ExecutorService service) {
			service.close();
		}
	}

	public static <T> HomeSection<T> join(CompletableFuture<HomeSection<T>> section) {
		return section.join();
	}

	private static <T> HomeSection<T> toSection(String name, T value, Throwable error, Predicate<T> isEmpty) {
		if (error != null) {
			Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
			if (cause instanceof TimeoutException) {
				log.warn("홈 섹션 시간 초과: {}", name);
				return HomeSection.failed(HomeSection.Failure.TIMEOUT);
			}
			log.warn("홈 섹션 실패: {} ({}: {})", name, cause.getClass().getSimpleName(), cause.getMessage());
			return HomeSection.failed(HomeSection.Failure.ERROR);
		}
		if (value == null || isEmpty.test(value)) {
			return HomeSection.empty(value);
		}
		return HomeSection.ok(value);
	}

}
