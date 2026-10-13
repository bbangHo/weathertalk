package kr.weathertalk.member.internal.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 제공자 연결 해제 작업 (FR-MEM-09 규칙 3~5, FR-MEM-07). 탈퇴·로그인 계정 해제 트랜잭션에서 기록되고 커밋 후 비동기로 실행된다.
 * 회원 ID를 두지 않는다(탈퇴 후 추적 불가). 자격은 암호문 그대로 들고 있다가 성공·취소 즉시, 실패는 보관 기한 뒤 파기한다.
 */
@Entity
@Table(name = "member_external_unlink")
public class ExternalUnlinkJob {

	public enum Reason {

		WITHDRAWAL, ACCOUNT_UNLINK

	}

	public enum Status {

		PENDING, SUCCEEDED, FAILED, CANCELLED,

		/** 필요한 자격이 없어 호출하지 않음(빈 자격 호출 금지, legacy 01-#4) */
		SKIPPED

	}

	@Id
	private UUID id;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private AuthProvider provider;

	@Column(nullable = false)
	private String providerUserId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Reason reason;

	private String credential;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Status status;

	@Column(nullable = false)
	private int attempts;

	@Column(nullable = false)
	private Instant nextAttemptAt;

	private Instant leaseUntil;

	private String lastError;

	@Column(nullable = false)
	private Instant createdAt;

	private Instant completedAt;

	protected ExternalUnlinkJob() {
	}

	public static ExternalUnlinkJob record(LoginAccount account, Reason reason, Instant now) {
		ExternalUnlinkJob job = new ExternalUnlinkJob();
		job.id = UUID.randomUUID();
		job.provider = account.provider();
		job.providerUserId = account.providerUserId();
		job.reason = reason;
		job.credential = account.unlinkCredential();
		job.status = Status.PENDING;
		job.attempts = 0;
		job.nextAttemptAt = now;
		job.createdAt = now;
		return job;
	}

	public void succeed(String detail, Instant now) {
		finish(Status.SUCCEEDED, detail, now);
	}

	public void cancel(String detail, Instant now) {
		finish(Status.CANCELLED, detail, now);
	}

	public void skip(String detail, Instant now) {
		finish(Status.SKIPPED, detail, now);
	}

	/** 영구 실패 또는 재시도 소진. 자격은 보관 기한까지 남겨 운영자가 수동 처리할 수 있게 한다. */
	public void fail(String detail, Instant now) {
		attempts++;
		status = Status.FAILED;
		lastError = truncate(detail);
		completedAt = now;
		leaseUntil = null;
	}

	/** 일시 오류: 시도 횟수를 올리고 backoff × 2^(시도-1) 뒤로 미룬다. 최대 횟수에 닿으면 실패로 끝난다. */
	public void retryLater(String detail, Instant now, int maxAttempts, Duration backoff) {
		attempts++;
		lastError = truncate(detail);
		leaseUntil = null;
		if (attempts >= maxAttempts) {
			status = Status.FAILED;
			completedAt = now;
			return;
		}
		nextAttemptAt = now.plus(backoff.multipliedBy(1L << Math.min(attempts - 1, 20)));
	}

	public void purgeCredential() {
		credential = null;
	}

	private void finish(Status finalStatus, String detail, Instant now) {
		if (finalStatus == Status.SUCCEEDED) {
			attempts++;
		}
		status = finalStatus;
		lastError = finalStatus == Status.SUCCEEDED ? null : truncate(detail);
		completedAt = now;
		credential = null;
		leaseUntil = null;
	}

	private static String truncate(String detail) {
		if (detail == null) {
			return null;
		}
		return detail.length() > 500 ? detail.substring(0, 500) : detail;
	}

	public UUID id() {
		return id;
	}

	public AuthProvider provider() {
		return provider;
	}

	public String providerUserId() {
		return providerUserId;
	}

	public Reason reason() {
		return reason;
	}

	public String credential() {
		return credential;
	}

	public Status status() {
		return status;
	}

	public int attempts() {
		return attempts;
	}

	public Instant nextAttemptAt() {
		return nextAttemptAt;
	}

	public String lastError() {
		return lastError;
	}

}
