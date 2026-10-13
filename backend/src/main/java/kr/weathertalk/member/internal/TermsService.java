package kr.weathertalk.member.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.weathertalk.member.internal.domain.Terms;
import kr.weathertalk.member.internal.domain.TermsAgreement;
import kr.weathertalk.member.internal.domain.TermsAgreementRepository;
import kr.weathertalk.member.internal.domain.TermsRepository;
import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;

/**
 * 약관 시행 버전·동의 이력·재동의 판정 (01-member.md §3.5, FR-MEM-03 규칙 2, FR-MEM-12, BR-MEM-06·16).
 * <p>
 * "약관 재동의 필요"는 저장하지 않고 동의 이력과 시행 버전을 비교해 매번 판정한다:
 * 필수 약관 종류마다, 시행된 마지막 중대 개정 버전 이상에 동의한 이력이 없으면 재동의가 필요하다.
 * 중대 개정이 아닌 새 버전은 재동의 없이 공지만 한다.
 */
@Service
public class TermsService {

	/** 제출된 약관별 동의 여부. */
	public record AgreementInput(String kind, Integer version, boolean agreed) {
	}

	private final TermsRepository termsRepository;
	private final TermsAgreementRepository agreementRepository;
	private final Clock clock;

	TermsService(TermsRepository termsRepository, TermsAgreementRepository agreementRepository, Clock clock) {
		this.termsRepository = termsRepository;
		this.agreementRepository = agreementRepository;
		this.clock = clock;
	}

	/** 종류별 현재 시행 버전. */
	@Transactional(readOnly = true)
	public List<Terms> currentTerms() {
		return currentByKind(termsRepository.findEffective(clock.instant())).values().stream().toList();
	}

	/** 재동의가 필요한 필수 약관(현재 시행 버전). 비어 있으면 재동의 불필요. */
	@Transactional(readOnly = true)
	public List<Terms> termsRequiringReagreement(UUID memberId) {
		List<Terms> effective = termsRepository.findEffective(clock.instant());
		Map<String, Terms> current = currentByKind(effective);
		Map<String, Integer> agreedMax = agreementRepository.findByMemberId(memberId)
			.stream()
			.collect(Collectors.toMap(TermsAgreement::kind, TermsAgreement::version, Math::max));
		List<Terms> result = new ArrayList<>();
		for (Terms terms : current.values()) {
			if (!terms.required()) {
				continue;
			}
			int lastMajor = effective.stream()
				.filter(t -> t.kind().equals(terms.kind()) && t.majorRevision())
				.mapToInt(Terms::version)
				.max()
				.orElse(effective.stream().filter(t -> t.kind().equals(terms.kind())).mapToInt(Terms::version).min().orElseThrow());
			Integer agreed = agreedMax.get(terms.kind());
			if (agreed == null || agreed < lastMajor) {
				result.add(terms);
			}
		}
		return result;
	}

	@Transactional(readOnly = true)
	public boolean reagreementRequired(UUID memberId) {
		return !termsRequiringReagreement(memberId).isEmpty();
	}

	/**
	 * 가입 완료: 현재 시행 중인 필수 약관 전부에 현재 버전으로 동의했는지 서버가 검사하고(BR-MEM-06, legacy 01-#5),
	 * 동의한 현재 버전 약관(필수·선택)의 이력을 남긴다. 호출자의 트랜잭션 안에서 실행된다.
	 */
	public void recordSignupAgreements(UUID memberId, Collection<AgreementInput> inputs) {
		List<Terms> current = currentTerms();
		requireAgreed(current.stream().filter(Terms::required).toList(), inputs);
		record(memberId, current, inputs);
	}

	/** 약관 재동의 (FR-MEM-12): 재동의가 필요한 필수 약관 전부가 현재 버전으로 동의돼야 한다. */
	@Transactional
	public void reagree(UUID memberId, Collection<AgreementInput> inputs) {
		requireAgreed(termsRequiringReagreement(memberId), inputs);
		record(memberId, currentTerms(), inputs);
	}

	private static void requireAgreed(List<Terms> mustAgree, Collection<AgreementInput> inputs) {
		List<String> missing = mustAgree.stream()
			.filter(terms -> inputs == null || inputs.stream().noneMatch(input -> matches(input, terms) && input.agreed()))
			.map(Terms::kind)
			.toList();
		if (!missing.isEmpty()) {
			throw new DomainException(ErrorCode.REQUIRED_TERMS_NOT_AGREED,
					"현재 시행 중인 필수 약관에 동의해야 합니다.", Map.of("missingTerms", missing));
		}
	}

	/** (회원, 종류, 버전)당 1건: 이미 있으면 새로 만들지 않는다(재제출 멱등, legacy 01-#6). */
	private void record(UUID memberId, List<Terms> current, Collection<AgreementInput> inputs) {
		Instant now = clock.instant();
		for (Terms terms : current) {
			boolean agreed = inputs != null && inputs.stream().anyMatch(input -> matches(input, terms) && input.agreed());
			TermsAgreement.Key key = new TermsAgreement.Key(memberId, terms.kind(), terms.version());
			if (agreed && !agreementRepository.existsById(key)) {
				agreementRepository.save(TermsAgreement.of(memberId, terms.kind(), terms.version(), now));
			}
		}
	}

	private static boolean matches(AgreementInput input, Terms terms) {
		return terms.kind().equals(input.kind()) && Integer.valueOf(terms.version()).equals(input.version());
	}

	private static Map<String, Terms> currentByKind(List<Terms> effective) {
		Map<String, Terms> current = new LinkedHashMap<>();
		effective.stream()
			.sorted(Comparator.comparing(Terms::kind).thenComparing(Terms::version))
			.forEach(terms -> current.put(terms.kind(), terms));
		return current;
	}

}
