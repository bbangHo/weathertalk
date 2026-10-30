package kr.weathertalk;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistrar;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import kr.weathertalk.member.internal.NicknameGenerator;
import kr.weathertalk.member.provider.ProviderTestKeys;
import kr.weathertalk.notification.RecordingPushSender;

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

	/** 외부 날씨 원천(기상청 /kma, 에어코리아 /air) 모의 서버. 모든 통합 테스트가 하나를 공유하며 각 테스트가 resetAll()한다. */
	@Bean(destroyMethod = "stop")
	WireMockServer weatherSourceServer() {
		WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
		server.start();
		return server;
	}

	@Bean
	DynamicPropertyRegistrar weatherSourceProperties(WireMockServer weatherSourceServer) {
		return registry -> {
			registry.add("weather.source.kma.base-url", () -> weatherSourceServer.baseUrl() + "/kma");
			registry.add("weather.source.kma.service-key", () -> "test-kma-key");
			registry.add("weather.source.air-korea.base-url", () -> weatherSourceServer.baseUrl() + "/air");
			registry.add("weather.source.air-korea.service-key", () -> "test-air-key");
		};
	}

	/**
	 * 소셜 로그인 제공자(카카오 /kakao, 애플 /apple, 구글 /google, 앱인토스 /toss) 모의 서버 연결. 키는 테스트용(ProviderTestKeys)이다.
	 */
	@Bean
	DynamicPropertyRegistrar loginProviderProperties(WireMockServer weatherSourceServer) {
		return registry -> {
			String base = weatherSourceServer.baseUrl();
			registry.add("member.providers.kakao.api-base-url", () -> base + "/kakao");
			registry.add("member.providers.kakao.app-id", () -> ProviderTestKeys.KAKAO_APP_ID);
			registry.add("member.providers.kakao.admin-key", () -> ProviderTestKeys.KAKAO_ADMIN_KEY);
			registry.add("member.providers.apple.base-url", () -> base + "/apple");
			registry.add("member.providers.apple.client-ids", () -> ProviderTestKeys.APPLE_CLIENT_ID);
			registry.add("member.providers.apple.team-id", () -> ProviderTestKeys.APPLE_TEAM_ID);
			registry.add("member.providers.apple.key-id", () -> ProviderTestKeys.APPLE_KEY_ID);
			registry.add("member.providers.apple.private-key-path",
					() -> ProviderTestKeys.APPLE_PRIVATE_KEY_PATH.toString());
			registry.add("member.providers.google.jwks-url", () -> base + "/google/certs");
			registry.add("member.providers.google.client-ids", () -> String.join(",", ProviderTestKeys.GOOGLE_CLIENT_IDS));
			registry.add("member.providers.google.token-url", () -> base + "/google/token");
			registry.add("member.providers.google.revoke-url", () -> base + "/google/revoke");
			registry.add("member.providers.google.server-client-id", () -> ProviderTestKeys.GOOGLE_SERVER_CLIENT_ID);
			registry.add("member.providers.google.server-client-secret",
					() -> ProviderTestKeys.GOOGLE_SERVER_CLIENT_SECRET);
			registry.add("member.providers.toss-anon.api-base-url", () -> base + "/toss");
			registry.add("member.providers.toss-anon.certificate-path", () -> ProviderTestKeys.TOSS_MTLS_CERT_PATH.toString());
			registry.add("member.providers.toss-anon.private-key-path", () -> ProviderTestKeys.TOSS_MTLS_KEY_PATH.toString());
		};
	}

	/** 푸시 발송을 기록하는 가짜 발송기(FCM 어댑터 대신). 테스트마다 clear()한다. */
	@Bean
	@Primary
	RecordingPushSender recordingPushSender() {
		return new RecordingPushSender();
	}

	@Bean
	ThreadSafeEventRecorder threadSafeEventRecorder() {
		return new ThreadSafeEventRecorder();
	}

	/**
	 * 여러 스레드에서 발행된 이벤트를 모은다. @RecordApplicationEvents(ApplicationEvents)는 테스트 스레드에서 발행된 것만 담아
	 * 동시 요청 테스트에서는 이걸 쓴다. 발행 즉시(트랜잭션 안) 받으므로 커밋된 발행만 세려면 성공한 요청에서만 발행하는 이벤트에 쓴다.
	 */
	public static class ThreadSafeEventRecorder {

		private final Queue<Object> events = new ConcurrentLinkedQueue<>();

		@org.springframework.context.event.EventListener
		void on(kr.weathertalk.community.PostLiked event) {
			events.add(event);
		}

		@org.springframework.context.event.EventListener
		void on(kr.weathertalk.weather.FieldReportPrecipitationReached event) {
			events.add(event);
		}

		@org.springframework.context.event.EventListener
		void on(kr.weathertalk.weather.PrecipitationStartForecasted event) {
			events.add(event);
		}

		public void clear() {
			events.clear();
		}

		public <T> java.util.List<T> of(Class<T> type) {
			return events.stream().filter(type::isInstance).map(type::cast).toList();
		}

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
