package kr.weathertalk.member.internal.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 가입 티켓 (01-member.md §3.4). 서버에 해시로 보관해 위조·변조를 막고 1회만 쓴다.
 * 제공자 연결 해제용 자격은 M7(소셜 로그인)에서 추가한다.
 */
@Entity
@Table(name = "member_signup_ticket")
public class SignupTicket {

	@Id
	private String tokenHash;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private AuthProvider provider;

	@Column(nullable = false)
	private String providerUserId;

	private String email;

	@Column(nullable = false)
	private Instant issuedAt;

	@Column(nullable = false)
	private Instant expiresAt;

	private Instant usedAt;

	protected SignupTicket() {
	}

	public static SignupTicket issue(String tokenHash, AuthProvider provider, String providerUserId, String email,
			Instant now, Instant expiresAt) {
		SignupTicket ticket = new SignupTicket();
		ticket.tokenHash = tokenHash;
		ticket.provider = provider;
		ticket.providerUserId = providerUserId;
		ticket.email = Member.normalizeEmail(email);
		ticket.issuedAt = now;
		ticket.expiresAt = expiresAt;
		return ticket;
	}

	public boolean expired(Instant now) {
		return !now.isBefore(expiresAt);
	}

	public boolean used() {
		return usedAt != null;
	}

	public void markUsed(Instant now) {
		usedAt = now;
	}

	public AuthProvider provider() {
		return provider;
	}

	public String providerUserId() {
		return providerUserId;
	}

	public String email() {
		return email;
	}

}
