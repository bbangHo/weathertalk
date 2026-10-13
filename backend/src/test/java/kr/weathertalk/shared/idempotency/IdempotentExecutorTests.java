package kr.weathertalk.shared.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import kr.weathertalk.TestcontainersConfiguration;
import kr.weathertalk.shared.Ids.MemberId;
import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;

/**
 * 요청 식별자 멱등 처리 (contracts.md §4 쓰기 요청 중복 방지).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class IdempotentExecutorTests {

	@Autowired
	IdempotentExecutor executor;

	@Autowired
	StringRedisTemplate redis;

	record Created(UUID id, String note) {
	}

	@Test
	void concurrentDuplicateRequestsRunTheActionOnce() throws Exception {
		MemberId requester = MemberId.random();
		IdempotencyKey key = newKey();
		AtomicInteger runs = new AtomicInteger();
		int threads = 16;
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		List<Future<Object>> futures = new ArrayList<>();
		try {
			for (int i = 0; i < threads; i++) {
				futures.add(pool.submit(() -> {
					start.await();
					try {
						return executor.execute(requester, "test.create", key, Created.class, () -> {
							runs.incrementAndGet();
							sleep(300);
							return new Created(UUID.randomUUID(), "첫 결과");
						});
					}
					catch (DomainException e) {
						return e.code();
					}
				}));
			}
			start.countDown();

			List<Object> outcomes = new ArrayList<>();
			for (Future<Object> f : futures) {
				outcomes.add(f.get());
			}

			assertThat(runs).hasValue(1);
			List<Created> results = outcomes.stream().filter(Created.class::isInstance).map(Created.class::cast).toList();
			assertThat(results).isNotEmpty();
			assertThat(results).allMatch(r -> r.equals(results.getFirst()));
			assertThat(outcomes).allMatch(o -> o instanceof Created || o == ErrorCode.REQUEST_IN_PROGRESS);

			// 처리 완료 후 재요청은 첫 결과를 그대로 받는다.
			Created replay = executor.execute(requester, "test.create", key, Created.class, () -> {
				runs.incrementAndGet();
				return new Created(UUID.randomUUID(), "다른 결과");
			});
			assertThat(replay).isEqualTo(results.getFirst());
			assertThat(runs).hasValue(1);
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	void requestWhileFirstIsProcessingGetsInProgress() throws Exception {
		MemberId requester = MemberId.random();
		IdempotencyKey key = newKey();
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Thread first = Thread.ofVirtual().start(() -> executor.execute(requester, "test.create", key, Created.class, () -> {
			entered.countDown();
			await(release);
			return new Created(UUID.randomUUID(), "첫 결과");
		}));
		entered.await();

		assertThatThrownBy(() -> executor.execute(requester, "test.create", key, Created.class,
				() -> new Created(UUID.randomUUID(), "중복")))
			.isInstanceOfSatisfying(DomainException.class,
					e -> assertThat(e.code()).isEqualTo(ErrorCode.REQUEST_IN_PROGRESS));

		release.countDown();
		first.join();
	}

	@Test
	void sameKeyFromDifferentRequestersOrOperationsIsIndependent() {
		IdempotencyKey key = newKey();
		AtomicInteger runs = new AtomicInteger();

		executor.execute(MemberId.random(), "test.create", key, Created.class, () -> create(runs));
		executor.execute(MemberId.random(), "test.create", key, Created.class, () -> create(runs));
		MemberId requester = MemberId.random();
		executor.execute(requester, "test.create", key, Created.class, () -> create(runs));
		executor.execute(requester, "test.other", key, Created.class, () -> create(runs));

		assertThat(runs).hasValue(4);
	}

	@Test
	void failedRequestReleasesKeySoRetryRuns() {
		MemberId requester = MemberId.random();
		IdempotencyKey key = newKey();
		AtomicInteger runs = new AtomicInteger();

		assertThatThrownBy(() -> executor.execute(requester, "test.create", key, Created.class, () -> {
			runs.incrementAndGet();
			throw new DomainException(ErrorCode.INVALID_REQUEST, "실패");
		})).isInstanceOf(DomainException.class);

		Created retried = executor.execute(requester, "test.create", key, Created.class, () -> create(runs));
		assertThat(retried).isNotNull();
		assertThat(runs).hasValue(2);
	}

	@Test
	void resultIsKeptForGivenTtl() {
		MemberId requester = MemberId.random();
		IdempotencyKey key = newKey();

		executor.execute(requester, "test.ttl", key, Duration.ofMinutes(5), Created.class,
				() -> new Created(UUID.randomUUID(), "x"));

		Long ttlSeconds = redis.getExpire("idem:test.ttl:" + requester.value() + ":" + key.value());
		assertThat(ttlSeconds).isBetween(1L, 300L);
	}

	@Test
	void malformedKeyIsRejected() {
		assertThatThrownBy(() -> new IdempotencyKey("bad key!"))
			.isInstanceOfSatisfying(DomainException.class,
					e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST));
	}

	private static Created create(AtomicInteger runs) {
		runs.incrementAndGet();
		return new Created(UUID.randomUUID(), "생성");
	}

	private static IdempotencyKey newKey() {
		return new IdempotencyKey(UUID.randomUUID().toString());
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await();
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

}
