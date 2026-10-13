package kr.weathertalk.member.internal.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface LoginSessionRepository extends JpaRepository<LoginSession, UUID> {

	/** 같은 갱신 토큰으로 동시에 온 재발급을 줄 세워 세대가 두 번 오르지 않게 한다. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from LoginSession s where s.id = :id")
	Optional<LoginSession> findForUpdate(UUID id);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update LoginSession s set s.status = :revoked, s.revokedAt = :now "
			+ "where s.memberId = :memberId and s.status = :active")
	int updateStatusOfMember(UUID memberId, Instant now, LoginSession.Status active, LoginSession.Status revoked);

	default int revokeAllOf(UUID memberId, Instant now) {
		return updateStatusOfMember(memberId, now, LoginSession.Status.ACTIVE, LoginSession.Status.REVOKED);
	}

}
