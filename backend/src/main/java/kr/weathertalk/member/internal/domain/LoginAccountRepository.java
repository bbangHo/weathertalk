package kr.weathertalk.member.internal.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface LoginAccountRepository extends JpaRepository<LoginAccount, UUID> {

	Optional<LoginAccount> findByProviderAndProviderUserId(AuthProvider provider, String providerUserId);

	boolean existsByProviderAndProviderUserId(AuthProvider provider, String providerUserId);

	List<LoginAccount> findByMemberId(UUID memberId);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from LoginAccount a where a.memberId = :memberId")
	int deleteByMemberId(UUID memberId);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from LoginAccount a where a.provider = :provider and a.providerUserId = :providerUserId")
	int deleteByProviderAndProviderUserId(AuthProvider provider, String providerUserId);

}
