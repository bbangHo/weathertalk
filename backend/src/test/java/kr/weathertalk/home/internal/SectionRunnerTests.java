package kr.weathertalk.home.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import kr.weathertalk.home.HomeSection;

/**
 * 섹션별 독립 조회와 부분 실패 (07 §1.4): 한 섹션의 오류·시간 초과가 다른 섹션을 막지 않는다.
 */
class SectionRunnerTests {

	final SectionRunner runner = new SectionRunner(Executors.newVirtualThreadPerTaskExecutor(), Duration.ofMillis(200));

	@AfterEach
	void close() {
		runner.close();
	}

	@Test
	void eachSectionGetsItsOwnStatus() {
		CompletableFuture<HomeSection<String>> ok = runner.start("ok", () -> "값", String::isEmpty);
		CompletableFuture<HomeSection<String>> empty = runner.start("empty", () -> "", String::isEmpty);
		CompletableFuture<HomeSection<String>> error = runner.start("error", () -> {
			throw new IllegalStateException("하위 모듈 오류");
		}, String::isEmpty);
		CompletableFuture<HomeSection<String>> slow = runner.start("slow", () -> {
			sleep(2000);
			return "늦은 값";
		}, String::isEmpty);

		long started = System.nanoTime();
		assertThat(SectionRunner.join(ok)).isEqualTo(HomeSection.ok("값"));
		assertThat(SectionRunner.join(empty).status()).isEqualTo(HomeSection.Status.EMPTY);
		assertThat(SectionRunner.join(error)).isEqualTo(HomeSection.failed(HomeSection.Failure.ERROR));
		assertThat(SectionRunner.join(slow)).isEqualTo(HomeSection.failed(HomeSection.Failure.TIMEOUT));
		// 느린 섹션은 시간 제한에서 잘리고 전체 응답을 붙잡지 않는다
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1500));
	}

	@Test
	void dependentSectionIsNotQueriedWhenPrerequisiteFailed() {
		AtomicBoolean queried = new AtomicBoolean();
		CompletableFuture<HomeSection<String>> failed = runner.start("pre", () -> {
			throw new IllegalStateException("선행 실패");
		}, String::isEmpty);
		CompletableFuture<HomeSection<List<String>>> dependent = runner.after("dependent", failed, pre -> {
			queried.set(true);
			return List.of(pre);
		}, List::isEmpty);

		assertThat(SectionRunner.join(dependent)).isEqualTo(HomeSection.failed(HomeSection.Failure.PREREQUISITE_FAILED));
		assertThat(queried).isFalse();

		CompletableFuture<HomeSection<String>> ok = runner.start("pre-ok", () -> "추움", String::isEmpty);
		assertThat(SectionRunner.join(runner.after("dependent-ok", ok, pre -> List.of(pre + "→옷"), List::isEmpty)))
			.isEqualTo(HomeSection.ok(List.of("추움→옷")));
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

}
