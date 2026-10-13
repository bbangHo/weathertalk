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

	/** 암호화된 제공자 연결 해제용 자격(애플·구글). 카카오·자격 미확보면 null. */
	private String unlinkCredential;

	private Instant unlinkCredentialUpdatedAt;

	protected LoginAccount() {
	}

	public static LoginAccount link(UUID memberId, AuthProvider provider, String providerUserId, Instant now) {
		return link(memberId, provider, providerUserId, null, now);
	}

	public static LoginAccount link(UUID memberId, AuthProvider provider, String providerUserId,
			String encryptedUnlinkCredential, Instant now) {
		LoginAccount account = new LoginAccount();
		account.id = UUID.randomUUID();
		account.memberId = memberId;
		account.provider = provider;
		account.providerUserId = providerUserId;
		account.linkedAt = now;
		account.updateUnlinkCredential(encryptedUnlinkCredential, now);
		return account;
	}

	/** 로그인 때 새 자격을 받았으면 바꾼다(FR-MEM-02 규칙 2). 이번에 받지 못했으면 기존 자격을 유지한다. */
	public void updateUnlinkCredential(String encrypted, Instant now) {
		if (encrypted != null) {
			unlinkCredential = encrypted;
			unlinkCredentialUpdatedAt = now;
		}
	}

	public UUID id() {
		return id;
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

	public String unlinkCredential() {
		return unlinkCredential;
	}

}
