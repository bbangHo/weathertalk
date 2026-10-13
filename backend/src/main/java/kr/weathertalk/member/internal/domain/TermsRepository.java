package kr.weathertalk.member.internal.domain;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TermsRepository extends JpaRepository<Terms, Terms.Key> {

	@Query("select t from Terms t where t.effectiveAt <= :now order by t.kind, t.version")
	List<Terms> findEffective(Instant now);

}
