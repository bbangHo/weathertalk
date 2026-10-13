package kr.weathertalk;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import kr.weathertalk.member.internal.NicknameGenerator;

@TestConfiguration(proxyBeanMethods = false)
public class IntegrationTestConfiguration {

	@Bean
	@Primary
	MutableClock testClock() {
		return new MutableClock();
	}

	@Bean
	@Primary
	ScriptedNicknameGenerator scriptedNicknameGenerator() {
		return new ScriptedNicknameGenerator();
	}

	/** 정해 둔 후보를 먼저 내고, 없으면 무작위 후보를 낸다(닉네임 충돌·재시도 테스트용). */
	public static class ScriptedNicknameGenerator implements NicknameGenerator {

		private final Queue<String> scripted = new ConcurrentLinkedQueue<>();

		private int sequence;

		public void enqueue(String... candidates) {
			scripted.addAll(java.util.List.of(candidates));
		}

		public void clear() {
			scripted.clear();
		}

		@Override
		public synchronized String generate() {
			String next = scripted.poll();
			if (next != null) {
				return next;
			}
			return "테스트" + Long.toString(System.nanoTime() % 100000) + (sequence++ % 10);
		}

	}

}
