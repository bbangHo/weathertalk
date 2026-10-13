package kr.weathertalk.member.internal.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ExternalUnlinkJobRepository extends JpaRepository<ExternalUnlinkJob, UUID> {

	@Query("select j.id from ExternalUnlinkJob j where j.status = kr.weathertalk.member.internal.domain.ExternalUnlinkJob.Status.PENDING "
			+ "and j.nextAttemptAt <= :now and (j.leaseUntil is null or j.leaseUntil < :now) order by j.nextAttemptAt")
	List<UUID> findDueIds(Instant now, Limit limit);

	/** 실행 점유: 대기 중이고 아직 누가 잡지 않은 작업만 잡는다(다중 인스턴스에서 같은 작업을 두 번 호출하지 않게). */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update ExternalUnlinkJob j set j.leaseUntil = :until where j.id = :id "
			+ "and j.status = kr.weathertalk.member.internal.domain.ExternalUnlinkJob.Status.PENDING "
			+ "and j.nextAttemptAt <= :now and (j.leaseUntil is null or j.leaseUntil < :now)")
	int claim(UUID id, Instant now, Instant until);

	/** 실패 작업의 자격 보관 기한(member.external-unlink.retention)이 지나면 자격을 파기한다. */
	@Modifying
	@Query("update ExternalUnlinkJob j set j.credential = null where j.status = kr.weathertalk.member.internal.domain.ExternalUnlinkJob.Status.FAILED "
			+ "and j.credential is not null and j.createdAt < :before")
	int purgeFailedCredentials(Instant before);

}
