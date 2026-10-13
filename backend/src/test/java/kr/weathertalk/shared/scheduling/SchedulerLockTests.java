package kr.weathertalk.shared.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import kr.weathertalk.TestcontainersConfiguration;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * 정기 작업 잠금 (contracts.md §4 정기 작업, 05-#7 다중 인스턴스 중복 실행 방지의 기반).
 * <p>
 * 인스턴스 2개를 흉내 내려고, 운영 설정({@link SchedulerLockConfig})만 올린 애플리케이션 컨텍스트 2개가 같은 Redis를 공유하게 한다.
 * {@link ExampleJob}은 정기 작업 작성 예시이기도 하다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SchedulerLockTests {

	@Autowired
	RedisConnectionFactory sharedRedis;

	@Autowired
	StringRedisTemplate redis;

	AnnotationConfigApplicationContext instanceA;
	AnnotationConfigApplicationContext instanceB;

	@BeforeEach
	void startTwoInstances() {
		clearLocks();
		ExampleJob.runs.set(0);
		instanceA = startInstance();
		instanceB = startInstance();
	}

	@AfterEach
	void stopInstances() {
		instanceA.close();
		instanceB.close();
	}

	@Test
	void sameRoundRunsOnOnlyOneInstanceWhenFiredConcurrently() throws Exception {
		ExampleJob jobA = instanceA.getBean(ExampleJob.class);
		ExampleJob jobB = instanceB.getBean(ExampleJob.class);
		CountDownLatch start = new CountDownLatch(1);

		CompletableFuture<Void> a = CompletableFuture.runAsync(() -> fire(start, jobA));
		CompletableFuture<Void> b = CompletableFuture.runAsync(() -> fire(start, jobB));
		start.countDown();
		CompletableFuture.allOf(a, b).get();

		assertThat(ExampleJob.runs).hasValue(1);
	}

	@Test
	void lockAtLeastForSkipsSameRoundOnInstanceWhoseClockIsSlightlyBehind() {
		instanceA.getBean(ExampleJob.class).run();
		// 첫 실행이 끝난 직후라도 lockAtLeastFor 동안은 다른 인스턴스가 같은 회차를 다시 돌리지 않는다.
		instanceB.getBean(ExampleJob.class).run();

		assertThat(ExampleJob.runs).hasValue(1);
	}

	private AnnotationConfigApplicationContext startInstance() {
		LettuceConnectionFactory shared = (LettuceConnectionFactory) sharedRedis;
		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
		context.registerBean(RedisConnectionFactory.class, () -> new LettuceConnectionFactory(
				new RedisStandaloneConfiguration(shared.getHostName(), shared.getPort())));
		context.register(SchedulerLockConfig.class, ExampleJob.class);
		context.refresh();
		return context;
	}

	private void clearLocks() {
		Set<String> keys = redis.keys("job-lock:*");
		if (keys != null && !keys.isEmpty()) {
			redis.delete(keys);
		}
	}

	private static void fire(CountDownLatch start, ExampleJob job) {
		try {
			start.await();
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return;
		}
		job.run();
	}

	/**
	 * 정기 작업 예시: 이름은 {@code <모듈>.<작업>}, 스케줄은 Asia/Seoul,
	 * lockAtMostFor = 작업 최대 소요보다 넉넉히, lockAtLeastFor = 인스턴스 간 시계 차이보다 길게.
	 */
	public static class ExampleJob {

		static final AtomicInteger runs = new AtomicInteger();

		@Scheduled(cron = "0 */10 * * * *", zone = "Asia/Seoul")
		@SchedulerLock(name = "test.example-job", lockAtMostFor = "PT1M", lockAtLeastFor = "PT10S")
		public void run() {
			runs.incrementAndGet();
			try {
				Thread.sleep(300);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}

	}

}
