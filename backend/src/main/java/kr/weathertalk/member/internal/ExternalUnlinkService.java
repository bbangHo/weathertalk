package kr.weathertalk.member.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.annotation.PreDestroy;
import kr.weathertalk.member.internal.auth.ProviderCredentialCipher;
import kr.weathertalk.member.internal.auth.ProviderUnlinker;
import kr.weathertalk.member.internal.config.MemberProperties;
import kr.weathertalk.member.internal.config.MemberProviderProperties;
import kr.weathertalk.member.internal.domain.AuthProvider;
import kr.weathertalk.member.internal.domain.ExternalUnlinkJob;
import kr.weathertalk.member.internal.domain.ExternalUnlinkJobRepository;
import kr.weathertalk.member.internal.domain.LoginAccount;
import kr.weathertalk.member.internal.domain.LoginAccountRepository;
import kr.weathertalk.member.internal.domain.SignupTicketRepository;

/**
 * 제공자 연결 해제 (FR-MEM-09 규칙 3~5, FR-MEM-07, BR-MEM-10).
 * <p>
 * 탈퇴·계정 해제 트랜잭션 안에서 작업만 기록하고({@link #schedule}), 커밋된 뒤에 별도 스레드에서 실행한다 — 외부 호출이 로컬 확정을
 * 되돌리지 못하게(legacy 01-#3). 일시 오류는 member.external-unlink.backoff부터 두 배씩 늘려 max-attempts까지 재시도하고,
 * 끝내 실패하면 실패로 남기고 경고 로그만 낸다(탈퇴는 그대로). 실행 직전 같은 로그인 계정이 다시 연결돼 있으면(새 회원 가입 등) 취소한다.
 * 다중 인스턴스에서는 작업 점유(lease)로 같은 작업을 한 곳에서만 호출한다.
 */
@Service
public class ExternalUnlinkService {

	private static final Logger log = LoggerFactory.getLogger(ExternalUnlinkService.class);
	private static final int BATCH = 50;
	private static final Object AFTER_COMMIT_KEY = new Object();

	private final ExternalUnlinkJobRepository jobs;
	private final LoginAccountRepository accounts;
	private final SignupTicketRepository tickets;
	private final Map<AuthProvider, ProviderUnlinker> unlinkers;
	private final ProviderCredentialCipher cipher;
	private final MemberProperties.ExternalUnlink settings;
	private final Duration lease;
	private final Clock clock;
	private final TransactionTemplate tx;
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	ExternalUnlinkService(ExternalUnlinkJobRepository jobs, LoginAccountRepository accounts,
			SignupTicketRepository tickets, List<ProviderUnlinker> unlinkers, ProviderCredentialCipher cipher,
			MemberProperties properties, MemberProviderProperties providerProperties, Clock clock,
			PlatformTransactionManager transactionManager) {
		this.jobs = jobs;
		this.accounts = accounts;
		this.tickets = tickets;
		this.unlinkers = unlinkers.stream()
			.collect(Collectors.toUnmodifiableMap(ProviderUnlinker::provider, Function.identity()));
		this.cipher = cipher;
		this.settings = properties.externalUnlink();
		this.lease = providerProperties.timeout().multipliedBy(4).plusSeconds(30);
		this.clock = clock;
		this.tx = new TransactionTemplate(transactionManager);
	}

	/**
	 * 로그인 계정의 제공자 연결 해제 작업을 현재 트랜잭션에 기록한다. 커밋되면 곧바로 실행을 시도한다.
	 * 해제 기능이 없는 제공자(개발 전용 DEV)는 기록하지 않는다.
	 */
	void schedule(LoginAccount account, ExternalUnlinkJob.Reason reason) {
		if (!unlinkers.containsKey(account.provider())) {
			return;
		}
		jobs.save(ExternalUnlinkJob.record(account, reason, clock.instant()));
		if (TransactionSynchronizationManager.isSynchronizationActive()
				&& !TransactionSynchronizationManager.hasResource(AFTER_COMMIT_KEY)) {
			TransactionSynchronizationManager.bindResource(AFTER_COMMIT_KEY, Boolean.TRUE);
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

				@Override
				public void afterCommit() {
					executor.execute(ExternalUnlinkService.this::processDueQuietly);
				}

				@Override
				public void afterCompletion(int status) {
					TransactionSynchronizationManager.unbindResourceIfPossible(AFTER_COMMIT_KEY);
				}

			});
		}
	}

	/** 실행할 때가 된 작업을 처리한다(커밋 직후·정기 확인). @return 이번에 점유해 처리한 작업 수 */
	public int processDue() {
		List<UUID> due = jobs.findDueIds(clock.instant(), Limit.of(BATCH));
		int processed = 0;
		for (UUID id : due) {
			if (process(id)) {
				processed++;
			}
		}
		return processed;
	}

	/** 실패 작업 자격 보관 기한 경과분 파기(FR-MEM-09 규칙 4), 만료된 가입 티켓 정리. */
	public void purgeExpired() {
		Instant now = clock.instant();
		tx.executeWithoutResult(status -> {
			jobs.purgeFailedCredentials(now.minus(settings.retention()));
			tickets.deleteExpiredBefore(now.minus(Duration.ofDays(1)));
		});
	}

	private void processDueQuietly() {
		try {
			processDue();
		}
		catch (RuntimeException e) {
			log.warn("제공자 연결 해제 처리 중 오류 — 정기 확인에서 다시 시도합니다: {}", e.toString());
		}
	}

	private boolean process(UUID id) {
		Instant now = clock.instant();
		Boolean claimed = tx.execute(status -> jobs.claim(id, now, now.plus(lease)) == 1);
		if (!Boolean.TRUE.equals(claimed)) {
			return false;
		}
		ExternalUnlinkJob job = jobs.findById(id).orElseThrow();
		Decision decision = decide(job);
		tx.executeWithoutResult(status -> {
			ExternalUnlinkJob current = jobs.findById(id).orElseThrow();
			Instant at = clock.instant();
			switch (decision.kind()) {
				case CANCEL -> current.cancel(decision.detail(), at);
				case SKIP -> current.skip(decision.detail(), at);
				case DONE -> applyResult(current, decision.result(), at);
			}
			jobs.save(current);
		});
		return true;
	}

	private Decision decide(ExternalUnlinkJob job) {
		// 그사이 같은 로그인 계정이 다시 연결됐으면(새 회원 가입·다른 회원 연결) 그 연결을 끊지 않도록 취소한다(FR-MEM-09 규칙 3)
		if (accounts.existsByProviderAndProviderUserId(job.provider(), job.providerUserId())) {
			return new Decision(DecisionKind.CANCEL, "같은 로그인 계정이 다시 연결됨", null);
		}
		ProviderUnlinker unlinker = unlinkers.get(job.provider());
		String credential = null;
		if (unlinker.requiresCredential()) {
			credential = cipher.decrypt(job.credential());
			if (credential == null) {
				log.warn("제공자 연결 해제 생략 — 보관한 자격 없음: provider={}, job={}", job.provider(), job.id());
				return new Decision(DecisionKind.SKIP, "NO_CREDENTIAL", null);
			}
		}
		try {
			return new Decision(DecisionKind.DONE, null, unlinker.unlink(job.providerUserId(), credential));
		}
		catch (RuntimeException e) {
			return new Decision(DecisionKind.DONE, null, ProviderUnlinker.Result.transientFailure(e.toString()));
		}
	}

	private void applyResult(ExternalUnlinkJob job, ProviderUnlinker.Result result, Instant now) {
		switch (result.outcome()) {
			case SUCCEEDED -> job.succeed(result.detail(), now);
			case TRANSIENT_FAILURE -> job.retryLater(result.detail(), now, settings.maxAttempts(), settings.backoff());
			case PERMANENT_FAILURE -> job.fail(result.detail(), now);
		}
		if (job.status() == ExternalUnlinkJob.Status.FAILED) {
			// 운영 경보 대상: 탈퇴는 되돌리지 않고 기록만 남긴다(BR-MEM-10)
			log.warn("제공자 연결 해제 최종 실패: provider={}, job={}, attempts={}, error={}", job.provider(), job.id(),
					job.attempts(), job.lastError());
		}
	}

	@PreDestroy
	void shutdown() {
		executor.shutdownNow();
	}

	private enum DecisionKind {

		CANCEL, SKIP, DONE

	}

	private record Decision(DecisionKind kind, String detail, ProviderUnlinker.Result result) {
	}

}
