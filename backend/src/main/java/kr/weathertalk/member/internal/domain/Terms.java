package kr.weathertalk.member.internal.domain;

import java.io.Serializable;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/** 약관 버전 (01-member.md §3.5). 기준 데이터는 Flyway로만 적재한다(V3__member_terms_seed.sql). */
@Entity
@Table(name = "member_terms")
@IdClass(Terms.Key.class)
public class Terms {

	public record Key(String kind, Integer version) implements Serializable {
	}

	@Id
	private String kind;

	@Id
	private Integer version;

	@Column(nullable = false)
	private String title;

	@Column(nullable = false)
	private boolean required;

	@Column(nullable = false)
	private boolean majorRevision;

	@Column(nullable = false)
	private Instant effectiveAt;

	@Column(nullable = false)
	private String contentUrl;

	protected Terms() {
	}

	public String kind() {
		return kind;
	}

	public int version() {
		return version;
	}

	public String title() {
		return title;
	}

	public boolean required() {
		return required;
	}

	public boolean majorRevision() {
		return majorRevision;
	}

	public Instant effectiveAt() {
		return effectiveAt;
	}

	public String contentUrl() {
		return contentUrl;
	}

}
