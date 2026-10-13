package kr.weathertalk.member.internal.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 로그인 세션 (01-member.md §3.3). 갱신 토큰은 세션의 현재 세대만 유효하며 재발급마다 세대가 1씩 오른다.
 */
@Entity
@Table(name = "member_login_session")
public class LoginSession {

	public enum Status {
		ACTIVE, REVOKED
	}

	@Id
	private UUID id;

	@Column(nullable = false)
	private UUID memberId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Status status;

	@Column(nullable = false)
	private int generation;

	@Column(nullable = false)
	private Instant startedAt;

	private Instant rotatedAt;

	private Instant revokedAt;

	protected LoginSession() {
	}

	public static LoginSession start(UUID memberId, Instant now) {
		LoginSession session = new LoginSession();
		session.id = UUID.randomUUID();
		session.memberId = memberId;
		session.status = Status.ACTIVE;
		session.generation = 1;
		session.startedAt = now;
		return session;
	}

	public boolean active() {
		return status == Status.ACTIVE;
	}

	public void rotate(Instant now) {
		generation++;
		rotatedAt = now;
	}

	public void revoke(Instant now) {
		if (status == Status.ACTIVE) {
			status = Status.REVOKED;
			revokedAt = now;
		}
	}

	public UUID id() {
		return id;
	}

	public UUID memberId() {
		return memberId;
	}

	public int generation() {
		return generation;
	}

	public Instant rotatedAt() {
		return rotatedAt;
	}

}
