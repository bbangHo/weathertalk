package kr.weathertalk.notification.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.annotation.PreDestroy;
import kr.weathertalk.notification.DevicePlatform;
import kr.weathertalk.notification.DeviceStatus;
import kr.weathertalk.notification.internal.DeliveryStore.SkipReason;
import kr.weathertalk.notification.internal.config.NotificationProperties;
import kr.weathertalk.notification.internal.push.PushMessage;
import kr.weathertalk.notification.internal.push.PushResult;
import kr.weathertalk.notification.internal.push.PushSender;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 공통 발송 절차 (BR-NOTI-07~13).
 * <ol>
 * <li>예약: 발송 키가 유일한 발송 기록을 먼저 만든다. 이미 있으면 보내지 않는다 — 분산 락이 풀리거나 두 인스턴스가 같은 회차·이벤트를 처리해도
 * 중복 발송이 0건이다(BR-NOTI-10).</li>
 * <li>심야 금지 시간: 정기 날씨 알림은 적용하지 않고(ADR 0013), 강수 예보 알림은 보류, 현장 제보 강수·댓글 알림은 버린다(BR-NOTI-08).</li>
 * <li>쿨다운 선점(원자적). 성공하면 확정, 최종 실패·생략이면 푼다(BR-NOTI-07).</li>
 * <li>batch-size건씩 묶어 concurrency개 배치까지 동시에 보낸다. 배치마다 짧은 트랜잭션으로 결과를 반영한다 — 긴 트랜잭션 하나로 묶지 않고,
 * 한 건·한 배치의 실패가 다른 건을 막지 않는다(BR-NOTI-11).</li>
 * <li>건별 응답 분류: 성공 / 무효 토큰(즉시 기기 발송 중지, BR-NOTI-12) / 일시 오류(재시도, BR-NOTI-13) / 메시지 오류(최종 실패, 경보).</li>
 * </ol>
 * 실행 자원은 다른 모듈 스케줄과 나눠 쓰지 않도록 이 클래스 전용 실행기를 쓴다(legacy 06-#18).
 */
@Component
public class Dispatcher {

	private static final Logger log = LoggerFactory.getLogger(Dispatcher.class);
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final int RETRY_CLAIM_LIMIT = 500;

	/** 알림 1건의 내용. targetKey = 실제 동네 ID(동네 알림) 또는 게시글 ID(댓글 알림). */
	public record AlertMessage(NotificationKind kind, String templateId, String title, String body, String targetKey) {
	}

	public record Recipient(UUID deviceId, String pushToken, DevicePlatform platform) {
	}

	public record Candidate(Recipient recipient, AlertMessage message) {
	}

	public enum QuietPolicy {

		/** 정기 날씨 알림: 심야에도 보낸다 */
		EXEMPT,

		/** 강수 예보 알림: 금지 종료 시각까지 보류, 그때 holdDeadline(예상 시작)이 지났으면 버림 */
		HOLD,

		/** 현장 제보 강수·댓글 알림: 버림 */
		DROP

	}

	/**
	 * 발송 회차 (06 §2): 정기 날씨 알림은 (날짜, 시각), 이벤트 알림은 이벤트 1건, 댓글 알림은 댓글 1건.
	 *
	 * @param deadline 이 시각이 지나면 재시도하지 않고 최종 실패(BR-NOTI-13)
	 * @param cooldown 쿨다운 기간. 없으면(정기 날씨 알림) null
	 * @param holdDeadline 보류 건을 보낼 수 있는 마지막 시각(강수 예보의 예상 시작 시각)
	 */
	public record Round(NotificationKind kind, String roundKey, Instant deadline, Duration cooldown,
			QuietPolicy quietPolicy, Instant holdDeadline) {
	}

	public record Report(int sent, int failed, int retrying, int held, int skipped, int duplicates) {
	}

	private record Ready(UUID deliveryId, NotificationKind kind, String targetKey, Recipient recipient, String title,
			String body, Instant deadline, int attempts, boolean cooldown) {
	}

	private final DeliveryStore deliveries;
	private final CooldownStore cooldowns;
	private final DeviceService deviceService;
	private final PushSender sender;
	private final QuietHours quietHours;
	private final NotificationProperties properties;
	private final Clock clock;
	private final TransactionTemplate tx;
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
	private final Semaphore concurrency;

	Dispatcher(DeliveryStore deliveries, CooldownStore cooldowns, DeviceService deviceService, PushSender sender,
			QuietHours quietHours, NotificationProperties properties, Clock clock,
			PlatformTransactionManager transactionManager) {
		this.deliveries = deliveries;
		this.cooldowns = cooldowns;
		this.deviceService = deviceService;
		this.sender = sender;
		this.quietHours = quietHours;
		this.properties = properties;
		this.clock = clock;
		this.tx = new TransactionTemplate(transactionManager);
		// 이벤트 구독의 바깥 트랜잭션이 있어도 배치 단위로 따로 커밋한다
		this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.concurrency = new Semaphore(properties.send().concurrency());
	}

	/** 생략 기록만 남긴다(행위자 본인·동네 미확인·날씨 없음·지연 초과 등). */
	public void recordSkips(NotificationKind kind, String roundKey, List<SkippedTarget> skipped) {
		if (skipped.isEmpty()) {
			return;
		}
		Instant now = clock.instant();
		tx.executeWithoutResult(status -> skipped.forEach(
				s -> deliveries.recordSkip(kind, roundKey, s.targetKey(), s.deviceId(), s.reason(), now)));
	}

	public record SkippedTarget(UUID deviceId, String targetKey, SkipReason reason) {
	}

	public Report dispatch(Round round, List<Candidate> candidates) {
		Counters counters = new Counters();
		// 인스턴스끼리 같은 순서로 예약해 잠금 순서가 엇갈리지 않게 한다
		List<Candidate> sorted = candidates.stream()
			.sorted(Comparator.comparing((Candidate c) -> c.recipient().deviceId())
				.thenComparing(c -> c.message().targetKey()))
			.toList();
		List<Ready> ready = new ArrayList<>();
		int batchSize = properties.send().batchSize();
		for (int from = 0; from < sorted.size(); from += batchSize) {
			List<Candidate> chunk = sorted.subList(from, Math.min(sorted.size(), from + batchSize));
			List<Ready> chunkReady = tx.execute(status -> reserve(round, chunk, counters));
			ready.addAll(chunkReady);
		}
		send(ready, counters);
		Report report = counters.report();
		log.info("알림 발송 {} {}: {}", round.kind(), round.roundKey(), report);
		return report;
	}

	private List<Ready> reserve(Round round, List<Candidate> chunk, Counters counters) {
		Instant now = clock.instant();
		boolean quiet = round.quietPolicy() != QuietPolicy.EXEMPT && quietHours.contains(now);
		List<Ready> ready = new ArrayList<>();
		for (Candidate candidate : chunk) {
			AlertMessage message = candidate.message();
			Optional<UUID> reserved = deliveries.reserve(round.kind(), round.roundKey(), message.targetKey(),
					candidate.recipient().deviceId(), message.templateId(), round.deadline(), now);
			if (reserved.isEmpty()) {
				counters.duplicates.incrementAndGet();
				continue;
			}
			UUID id = reserved.get();
			if (quiet && round.quietPolicy() == QuietPolicy.DROP) {
				deliveries.skip(id, SkipReason.QUIET_HOURS, now);
				counters.skipped.incrementAndGet();
				continue;
			}
			if (quiet && round.quietPolicy() == QuietPolicy.HOLD) {
				deliveries.hold(id, payload(message.title(), message.body()), quietHours.endOf(now),
						round.holdDeadline(), now);
				counters.held.incrementAndGet();
				continue;
			}
			if (!sender.enabled()) {
				deliveries.skip(id, SkipReason.PUSH_DISABLED, now);
				counters.skipped.incrementAndGet();
				continue;
			}
			if (round.cooldown() != null && !cooldowns.claim(round.kind(), message.targetKey(),
					candidate.recipient().deviceId(), id, now, now.plus(round.cooldown()))) {
				deliveries.skip(id, SkipReason.COOLDOWN, now);
				counters.skipped.incrementAndGet();
				continue;
			}
			ready.add(new Ready(id, round.kind(), message.targetKey(), candidate.recipient(), message.title(),
					message.body(), round.deadline(), 0, round.cooldown() != null));
		}
		return ready;
	}

	/**
	 * 재시도 대기·보류 건 처리 (BR-NOTI-08·13). 정기 확인 작업이 부른다.
	 *
	 * @return 다시 보낸 결과
	 */
	public Report processDue() {
		Counters counters = new Counters();
		Instant now = clock.instant();
		List<DeliveryStore.Pending> due = tx
			.execute(status -> deliveries.claimDue(now, now.plus(Duration.ofMinutes(5)), RETRY_CLAIM_LIMIT));
		List<Ready> ready = new ArrayList<>();
		tx.executeWithoutResult(status -> {
			for (DeliveryStore.Pending pending : due) {
				prepareDue(pending, now, counters).ifPresent(ready::add);
			}
		});
		send(ready, counters);
		return counters.report();
	}

	private Optional<Ready> prepareDue(DeliveryStore.Pending pending, Instant now, Counters counters) {
		Duration cooldown = cooldownOf(pending.kind());
		if (pending.deviceStatus() != DeviceStatus.ACTIVE || pending.pushToken() == null) {
			deliveries.abandon(pending.id(), SkipReason.INVALID_DEVICE, now);
			cooldowns.release(pending.id());
			counters.failed.incrementAndGet();
			return Optional.empty();
		}
		String[] content = content(pending.payload());
		if (pending.status() == DeliveryStore.Status.HELD) {
			// 금지 종료 시각에 예상 시작 시각이 아직 미래면 보내고, 지났으면 버린다
			if (pending.deadline() == null || !now.isBefore(pending.deadline())) {
				deliveries.skip(pending.id(), SkipReason.QUIET_HOURS, now);
				counters.skipped.incrementAndGet();
				return Optional.empty();
			}
			if (quietHours.contains(now)) {
				deliveries.reschedule(pending.id(), quietHours.endOf(now), now);
				counters.held.incrementAndGet();
				return Optional.empty();
			}
			if (cooldown != null && !cooldowns.claim(pending.kind(), pending.targetKey(), pending.deviceId(),
					pending.id(), now, now.plus(cooldown))) {
				deliveries.skip(pending.id(), SkipReason.COOLDOWN, now);
				counters.skipped.incrementAndGet();
				return Optional.empty();
			}
		}
		else if (pending.deadline() != null && now.isAfter(pending.deadline())) {
			deliveries.abandon(pending.id(), SkipReason.RETRY_EXHAUSTED, now);
			cooldowns.release(pending.id());
			counters.failed.incrementAndGet();
			return Optional.empty();
		}
		if (content == null) {
			deliveries.abandon(pending.id(), SkipReason.MESSAGE_REJECTED, now);
			cooldowns.release(pending.id());
			counters.failed.incrementAndGet();
			return Optional.empty();
		}
		return Optional.of(new Ready(pending.id(), pending.kind(), pending.targetKey(),
				new Recipient(pending.deviceId(), pending.pushToken(), pending.platform()), content[0], content[1],
				pending.deadline(), pending.attempts(), cooldown != null));
	}

	private void send(List<Ready> ready, Counters counters) {
		if (ready.isEmpty()) {
			return;
		}
		int batchSize = properties.send().batchSize();
		List<Future<?>> futures = new ArrayList<>();
		for (int from = 0; from < ready.size(); from += batchSize) {
			List<Ready> batch = ready.subList(from, Math.min(ready.size(), from + batchSize));
			futures.add(executor.submit(() -> sendBatch(batch, counters)));
		}
		for (Future<?> future : futures) {
			try {
				future.get();
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
			catch (java.util.concurrent.ExecutionException e) {
				log.warn("알림 배치 처리 실패(다른 배치는 계속): {}", e.getCause().toString());
			}
		}
	}

	private void sendBatch(List<Ready> batch, Counters counters) {
		List<PushResult> results;
		try {
			concurrency.acquire();
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return;
		}
		try {
			results = sender.send(batch.stream().map(this::toPush).toList());
		}
		catch (RuntimeException e) {
			log.warn("푸시 배치 호출 실패 — 일시 오류로 처리: {}", e.toString());
			results = batch.stream().map(r -> PushResult.transientFailure("SENDER_ERROR")).toList();
		}
		finally {
			concurrency.release();
		}
		List<PushResult> finalResults = results;
		tx.executeWithoutResult(status -> {
			for (int i = 0; i < batch.size(); i++) {
				PushResult result = i < finalResults.size() ? finalResults.get(i)
						: PushResult.transientFailure("NO_RESULT");
				apply(batch.get(i), result, counters);
			}
		});
	}

	private void apply(Ready ready, PushResult result, Counters counters) {
		Instant now = clock.instant();
		switch (result.outcome()) {
			case SUCCESS -> {
				deliveries.succeed(ready.deliveryId(), now);
				if (ready.cooldown()) {
					cooldowns.confirm(ready.deliveryId(), now.plus(cooldownOf(ready.kind())));
				}
				counters.sent.incrementAndGet();
			}
			case INVALID_TOKEN -> {
				deliveries.fail(ready.deliveryId(), SkipReason.INVALID_DEVICE, result.code(), now);
				deviceService.suspendInvalidToken(ready.recipient().deviceId(), ready.recipient().pushToken());
				cooldowns.release(ready.deliveryId());
				counters.failed.incrementAndGet();
			}
			case TRANSIENT -> {
				int attempt = ready.attempts() + 1;
				Instant next = now.plus(properties.send().retryBackoff().multipliedBy(1L << Math.min(attempt - 1, 20)));
				boolean exhausted = attempt > properties.send().maxRetries()
						|| (ready.deadline() != null && next.isAfter(ready.deadline()));
				if (exhausted) {
					deliveries.fail(ready.deliveryId(), SkipReason.RETRY_EXHAUSTED, result.code(), now);
					cooldowns.release(ready.deliveryId());
					counters.failed.incrementAndGet();
				}
				else {
					deliveries.retryLater(ready.deliveryId(), result.code(), payload(ready.title(), ready.body()), next,
							now);
					counters.retrying.incrementAndGet();
				}
			}
			case MESSAGE_ERROR -> {
				log.warn("푸시 메시지 거절(운영 확인 필요): kind={}, delivery={}, code={}", ready.kind(),
						ready.deliveryId(), result.code());
				deliveries.fail(ready.deliveryId(), SkipReason.MESSAGE_REJECTED, result.code(), now);
				cooldowns.release(ready.deliveryId());
				counters.failed.incrementAndGet();
			}
		}
	}

	private PushMessage toPush(Ready ready) {
		Map<String, String> data = new LinkedHashMap<>();
		data.put("kind", ready.kind().name());
		data.put(ready.kind() == NotificationKind.COMMENT ? "postId" : "neighborhoodId", ready.targetKey());
		data.put("target", ready.kind().navigationTarget());
		data.put("deliveryKey", ready.deliveryId().toString());
		return new PushMessage(ready.recipient().pushToken(), ready.recipient().platform(), ready.title(), ready.body(),
				data, ready.kind().name() + ":" + ready.targetKey());
	}

	Duration cooldownOf(NotificationKind kind) {
		return switch (kind) {
			case DAILY_WEATHER -> null;
			case PRECIPITATION_FORECAST, FIELD_REPORT_PRECIPITATION -> properties.cooldown();
			case COMMENT -> properties.comment().cooldown();
		};
	}

	private static String payload(String title, String body) {
		return JSON.createObjectNode().put("title", title).put("body", body).toString();
	}

	private static String[] content(String payload) {
		if (payload == null) {
			return null;
		}
		try {
			JsonNode node = JSON.readTree(payload);
			return new String[] { node.path("title").asString(), node.path("body").asString() };
		}
		catch (JacksonException e) {
			return null;
		}
	}

	@PreDestroy
	void shutdown() {
		executor.shutdownNow();
	}

	private static final class Counters {

		final AtomicInteger sent = new AtomicInteger();
		final AtomicInteger failed = new AtomicInteger();
		final AtomicInteger retrying = new AtomicInteger();
		final AtomicInteger held = new AtomicInteger();
		final AtomicInteger skipped = new AtomicInteger();
		final AtomicInteger duplicates = new AtomicInteger();

		Report report() {
			return new Report(sent.get(), failed.get(), retrying.get(), held.get(), skipped.get(), duplicates.get());
		}

	}

}
