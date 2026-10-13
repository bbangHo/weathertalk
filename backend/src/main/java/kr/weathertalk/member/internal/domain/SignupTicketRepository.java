package kr.weathertalk.member.internal.domain;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface SignupTicketRepository extends JpaRepository<SignupTicket, String> {

	/** 같은 티켓으로 동시에 온 가입 완료 요청을 줄 세운다(중복 탭 멱등, FR-MEM-03 규칙 5). */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select t from SignupTicket t where t.tokenHash = :tokenHash")
	Optional<SignupTicket> findForUpdate(String tokenHash);

	/** 만료된 티켓 정리(들고 있던 제공자 자격도 함께 사라진다). */
	@Modifying
	@Query("delete from SignupTicket t where t.expiresAt < :before")
	int deleteExpiredBefore(Instant before);

}
