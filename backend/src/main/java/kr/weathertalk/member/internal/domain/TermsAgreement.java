package kr.weathertalk.member.internal.domain;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/** 약관 동의 이력: (회원, 종류, 버전)당 1건, 동의 시각 포함 (BR-MEM-06). */
@Entity
@Table(name = "member_terms_agreement")
@IdClass(TermsAgreement.Key.class)
public class TermsAgreement {

	public record Key(UUID memberId, String kind, Integer version) implements Serializable {
	}

	@Id
	private UUID memberId;

	@Id
	private String kind;

	@Id
	private Integer version;

	@Column(nullable = false)
	private Instant agreedAt;

	protected TermsAgreement() {
	}

	public static TermsAgreement of(UUID memberId, String kind, int version, Instant now) {
		TermsAgreement agreement = new TermsAgreement();
		agreement.memberId = memberId;
		agreement.kind = kind;
		agreement.version = version;
		agreement.agreedAt = now;
		return agreement;
	}

	public String kind() {
		return kind;
	}

	public int version() {
		return version;
	}

	public Instant agreedAt() {
		return agreedAt;
	}

}
