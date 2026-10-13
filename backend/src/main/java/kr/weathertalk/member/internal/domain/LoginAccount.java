package kr.weathertalk.member.internal.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 로그인 계정 (01-member.md §3.2, ADR 0006): (제공자, 제공자 고유 ID)로 회원을 찾는다. */
@Entity
@Table(name = "member_login_account")
public class LoginAccount {

	@Id
	private UUID id;

	@Column(nullable = false)
	private UUID memberId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private AuthProvider provider;

	@Column(nullable = false)
	private String providerUserId;

	@Column(nullable = false)
	private Instant linkedAt;

	protected LoginAccount() {
	}

	public static LoginAccount link(UUID memberId, AuthProvider provider, String providerUserId, Instant now) {
		LoginAccount account = new LoginAccount();
		account.id = UUID.randomUUID();
		account.memberId = memberId;
		account.provider = provider;
		account.providerUserId = providerUserId;
		account.linkedAt = now;
		return account;
	}

	public UUID memberId() {
		return memberId;
	}

	public AuthProvider provider() {
		return provider;
	}

	public String providerUserId() {
		return providerUserId;
	}

}
