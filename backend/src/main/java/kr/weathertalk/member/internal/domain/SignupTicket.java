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
 * 로그인 때 받은 제공자 연결 해제용 자격(암호화)을 가입 완료까지 들고 간다.
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

	private String unlinkCredential;

	protected SignupTicket() {
	}

	public static SignupTicket issue(String tokenHash, AuthProvider provider, String providerUserId, String email,
			Instant now, Instant expiresAt) {
		return issue(tokenHash, provider, providerUserId, email, null, now, expiresAt);
	}

	public static SignupTicket issue(String tokenHash, AuthProvider provider, String providerUserId, String email,
			String encryptedUnlinkCredential, Instant now, Instant expiresAt) {
		SignupTicket ticket = new SignupTicket();
		ticket.tokenHash = tokenHash;
		ticket.provider = provider;
		ticket.providerUserId = providerUserId;
		ticket.email = Member.normalizeEmail(email);
		ticket.unlinkCredential = encryptedUnlinkCredential;
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

	/** 쓰고 나면 자격을 지운다(가입한 로그인 계정으로 옮겼거나 필요 없어짐). */
	public void markUsed(Instant now) {
		usedAt = now;
		unlinkCredential = null;
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

	public String unlinkCredential() {
		return unlinkCredential;
	}

}
