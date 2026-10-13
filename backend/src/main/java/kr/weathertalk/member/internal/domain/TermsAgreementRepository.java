package kr.weathertalk.member.internal.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TermsAgreementRepository extends JpaRepository<TermsAgreement, TermsAgreement.Key> {

	List<TermsAgreement> findByMemberId(UUID memberId);

}
