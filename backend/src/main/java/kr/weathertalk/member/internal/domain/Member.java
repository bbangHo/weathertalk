package kr.weathertalk.member.internal.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import kr.weathertalk.member.MemberProfile;
import kr.weathertalk.member.MemberStatus;
import kr.weathertalk.member.TemperatureSensitivity;
import kr.weathertalk.shared.Ids.MemberId;

/**
 * 회원 (01-member.md §3.1). 가입 완료 순간 약관 동의·온도 민감도·닉네임을 모두 갖춘 채 생긴다(BR-MEM-07).
 */
@Entity
@Table(name = "member_member")
public class Member {

	@Id
	private UUID id;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private MemberStatus status;

	private String nickname;

	private Instant nicknameChangedAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private TemperatureSensitivity temperatureSensitivity;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private MemberRole role;

	private String email;

	@Column(nullable = false)
	private Instant joinedAt;

	private Instant withdrawnAt;

	protected Member() {
	}

	public static Member register(String nickname, TemperatureSensitivity sensitivity, String email, Instant now) {
		Member member = new Member();
		member.id = UUID.randomUUID();
		member.status = MemberStatus.ACTIVE;
		member.nickname = nickname;
		member.temperatureSensitivity = sensitivity;
		member.role = MemberRole.USER;
		member.email = normalizeEmail(email);
		member.joinedAt = now;
		return member;
	}

	/** 제공자가 이메일을 주지 않으면 NULL로 둔다. "null" 같은 대체 문자열을 만들지 않는다(BR-MEM-03). */
	public static String normalizeEmail(String email) {
		if (email == null || email.isBlank() || email.strip().equalsIgnoreCase("null")) {
			return null;
		}
		return email.strip();
	}

	public MemberId memberId() {
		return new MemberId(id);
	}

	public boolean active() {
		return status == MemberStatus.ACTIVE;
	}

	public boolean admin() {
		return role == MemberRole.ADMIN;
	}

	/** @return 이전 값. 같으면 null(변경 없음). */
	public TemperatureSensitivity changeTemperatureSensitivity(TemperatureSensitivity sensitivity) {
		if (temperatureSensitivity == sensitivity) {
			return null;
		}
		TemperatureSensitivity previous = temperatureSensitivity;
		temperatureSensitivity = sensitivity;
		return previous;
	}

	public void changeNickname(String newNickname, Instant now) {
		nickname = newNickname;
		nicknameChangedAt = now;
	}

	/** 로컬 확정: 상태 탈퇴, 닉네임·이메일 삭제(닉네임은 즉시 해제된다, BR-MEM-08). */
	public void withdraw(Instant now) {
		status = MemberStatus.WITHDRAWN;
		withdrawnAt = now;
		nickname = null;
		email = null;
	}

	public MemberProfile toProfile() {
		return new MemberProfile(memberId(), nickname, temperatureSensitivity, status);
	}

	public UUID id() {
		return id;
	}

	public MemberStatus status() {
		return status;
	}

	public String nickname() {
		return nickname;
	}

	public Instant nicknameChangedAt() {
		return nicknameChangedAt;
	}

	public TemperatureSensitivity temperatureSensitivity() {
		return temperatureSensitivity;
	}

	public MemberRole role() {
		return role;
	}

	public String email() {
		return email;
	}

	public Instant joinedAt() {
		return joinedAt;
	}

}
