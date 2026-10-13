package kr.weathertalk.feeling.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.weathertalk.feeling.CalibrationOffer;
import kr.weathertalk.feeling.CalibrationOfferStatus;
import kr.weathertalk.feeling.internal.config.FeelingProperties;
import kr.weathertalk.member.MemberApi;
import kr.weathertalk.member.MemberProfile;
import kr.weathertalk.member.SensitivityChangeSource;
import kr.weathertalk.member.TemperatureSensitivity;
import kr.weathertalk.member.TemperatureSensitivityChanged;
import kr.weathertalk.shared.Ids.MemberId;
import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;

/**
 * 민감도 보정 제안 (FR-FEL-07·08, BR-FEL-17). 판정 배치, 조회, 수락·거절, 민감도 변경·탈퇴 반영.
 * <p>
 * 수락은 feeling이 member {@code 온도 민감도 변경}(출처 = 보정 제안 수락)을 호출한다. 근거: 04 FR-FEL-08 규칙 2("member의 온도 민감도 변경을
 * 호출하고 수락됨으로 바꾼다")와 04 §6.2(feeling이 member `온도 민감도 변경`을 보정 제안 수락 시에만 사용). 회원의 명시적 수락 요청에서만
 * 일어나므로 '자동 변경'이 아니다(BR-MEM-12). 같은 트랜잭션이라 member 변경이 실패하면 제안은 '제안됨'으로 남는다.
 * 앱이 member API를 출처 '보정 제안 수락'으로 직접 불러도 {@link #onSensitivityChanged}가 수락으로 처리한다.
 */
@Service
public class FeelingCalibrationService {

	private static final Logger log = LoggerFactory.getLogger(FeelingCalibrationService.class);

	private static final int PROFILE_BATCH = 500;

	/** 판정 실행 결과. */
	public record JudgeResult(int candidates, int judged, int offered, int expired) {
	}

	private final CalibrationOfferRepository offers;
	private final FeelingRecordRepository records;
	private final FeelingMappingLookup mapping;
	private final MemberApi memberApi;
	private final FeelingProperties properties;
	private final Clock clock;

	FeelingCalibrationService(CalibrationOfferRepository offers, FeelingRecordRepository records,
			FeelingMappingLookup mapping, MemberApi memberApi, FeelingProperties properties, Clock clock) {
		this.offers = offers;
		this.records = records;
		this.mapping = mapping;
		this.memberApi = memberApi;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * 판정 배치 (FR-FEL-07). 먼저 유효 기간이 지난 제안을 만료시키고, 쓸 수 있는 매핑(플래그 + 적용 중 버전)이 없으면 판정하지 않는다
	 * (규칙 7: 고정 기준표만으로 그룹을 바꾸라고 권하지 않는다). 제안 생성은 푸시·이벤트 없이 저장만 한다(Q61).
	 */
	public JudgeResult judgeAll() {
		Instant now = clock.instant();
		int expired = offers.expireOverdue(now);
		Optional<LoadedMapping> active = mapping.active();
		if (active.isEmpty()) {
			log.info("민감도 보정 제안 판정 건너뜀: 쓸 수 있는 체감 매핑 없음");
			return new JudgeResult(0, 0, 0, expired);
		}
		LoadedMapping loaded = active.get();
		FeelingProperties.Calibration config = properties.calibration();
		Map<UUID, List<MappingSample>> byMember = records.findSecuredMemberSamples(now.minus(config.lookback()))
			.stream()
			.collect(Collectors.groupingBy(MappingSample::memberId, LinkedHashMap::new, Collectors.toList()));
		Set<UUID> offered = offers.offeredMembers();
		Map<UUID, CalibrationOfferRepository.MemberState> states = offers.findStates(byMember.keySet());
		Map<MemberId, MemberProfile> profiles = profiles(byMember.keySet());

		int judged = 0;
		int created = 0;
		for (Map.Entry<UUID, List<MappingSample>> entry : byMember.entrySet()) {
			UUID memberId = entry.getKey();
			MemberProfile profile = profiles.get(new MemberId(memberId));
			if (profile == null || profile.withdrawn() || offered.contains(memberId)
					|| coolingDown(states.getOrDefault(memberId, CalibrationOfferRepository.MemberState.EMPTY), now)) {
				continue;
			}
			TemperatureSensitivity current = profile.temperatureSensitivity();
			Optional<CalibrationJudgement.Outcome> outcome = CalibrationJudgement.judge(entry.getValue(), current,
					config.feedbackWeight(), config.minRecords(), config.minMargin(),
					(group, s) -> FeelingMappingLookup.expected(active, mapping.referenceTable(), group, s.recordedAt(),
							s.temperature(), s.humidity(), s.windSpeed())
						.level());
			if (outcome.isEmpty()) {
				continue; // 기록 부족은 판정으로 치지 않는다(쿨다운 시작 안 함)
			}
			judged++;
			offers.markJudged(memberId, now);
			CalibrationJudgement.Outcome o = outcome.get();
			if (o.suggested() != null) {
				CalibrationOffer offer = new CalibrationOffer(UUID.randomUUID(), current, o.suggested(), o.recordCount(),
						o.weightSum(), o.errors(), now, now.plus(config.offerTtl()), CalibrationOfferStatus.OFFERED);
				if (offers.insertOffered(memberId, offer, loaded.version())) {
					created++;
				}
			}
		}
		log.info("민감도 보정 제안 판정: 대상 {}명, 판정 {}명, 제안 {}건, 만료 {}건", byMember.size(), judged, created, expired);
		return new JudgeResult(byMember.size(), judged, created, expired);
	}

	/** 빈도 제한(규칙 6): 마지막 판정·민감도 변경 후 cooldown, 마지막 거절 후 decline-cooldown이 지나지 않았으면 판정하지 않는다. */
	private boolean coolingDown(CalibrationOfferRepository.MemberState state, Instant now) {
		FeelingProperties.Calibration config = properties.calibration();
		return within(state.lastJudgedAt(), config.cooldown(), now)
				|| within(state.lastSensitivityChangedAt(), config.cooldown(), now)
				|| within(state.lastDeclinedAt(), config.declineCooldown(), now);
	}

	private static boolean within(Instant since, Duration period, Instant now) {
		return since != null && now.isBefore(since.plus(period));
	}

	private Map<MemberId, MemberProfile> profiles(Set<UUID> memberIds) {
		List<MemberId> ids = memberIds.stream().map(MemberId::new).toList();
		Map<MemberId, MemberProfile> result = new LinkedHashMap<>();
		for (int i = 0; i < ids.size(); i += PROFILE_BATCH) {
			result.putAll(memberApi.findProfiles(ids.subList(i, Math.min(ids.size(), i + PROFILE_BATCH))));
		}
		return result;
	}

	/** 조회 (FR-FEL-08 규칙 1): 유효한 '제안됨'이 있을 때만. */
	public Optional<CalibrationOffer> find(MemberId memberId) {
		return offers.findOffered(memberId.value(), clock.instant()).map(CalibrationOfferRepository.StoredOffer::offer);
	}

	/**
	 * 수락 (FR-FEL-08 규칙 2): 제안이 '제안됨'·유효 기간 안이고 회원의 현재 민감도가 판정 때 그룹과 같을 때만 member 민감도 변경
	 * (출처 = 보정 제안 수락)을 부르고 '수락됨'으로 바꾼다. 이미 수락된 제안이면 그대로 돌려준다(재시도 안전).
	 *
	 * @throws DomainException NOT_FOUND(없음·남의 제안), CALIBRATION_OFFER_NOT_ACTIVE, member 변경 실패 시 그 오류(제안은 그대로)
	 */
	@Transactional
	public CalibrationOffer accept(MemberId memberId, UUID offerId) {
		CalibrationOfferRepository.StoredOffer stored = requireOffer(memberId, offerId);
		if (stored.offer().status() == CalibrationOfferStatus.ACCEPTED) {
			return stored.offer();
		}
		Instant now = clock.instant();
		requireActive(stored, now);
		TemperatureSensitivity current = memberApi.findProfile(memberId)
			.filter(profile -> !profile.withdrawn())
			.map(MemberProfile::temperatureSensitivity)
			.orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "회원을 찾을 수 없습니다."));
		if (current != stored.offer().currentGroup()) {
			throw notActive("SENSITIVITY_CHANGED", "온도 민감도가 이미 바뀌었습니다.");
		}
		memberApi.changeTemperatureSensitivity(memberId, stored.offer().suggestedGroup(),
				SensitivityChangeSource.CALIBRATION_ACCEPTED);
		offers.updateStatus(offerId, CalibrationOfferStatus.ACCEPTED, now);
		return reload(offerId);
	}

	/**
	 * 거절 (FR-FEL-08 규칙 3): '거절됨'으로 바꾸고 거절 후 대기(decline-cooldown)를 시작한다. 이미 거절된 제안이면 그대로 돌려준다.
	 */
	@Transactional
	public CalibrationOffer decline(MemberId memberId, UUID offerId) {
		CalibrationOfferRepository.StoredOffer stored = requireOffer(memberId, offerId);
		if (stored.offer().status() == CalibrationOfferStatus.DECLINED) {
			return stored.offer();
		}
		Instant now = clock.instant();
		requireActive(stored, now);
		offers.updateStatus(offerId, CalibrationOfferStatus.DECLINED, now);
		offers.markDeclined(memberId.value(), now);
		return reload(offerId);
	}

	/**
	 * 온도민감도변경됨 반영 (04 §6.4): 쿨다운 기준 시각을 갱신하고, '제안됨' 제안이 있으면 출처가 보정 제안 수락이면서 제안 그룹으로 바뀐 경우
	 * '수락됨', 그 밖(직접 변경 등)은 '무효'로 바꾼다. 같은 이벤트가 다시 와도 결과가 같다.
	 */
	@Transactional
	public void onSensitivityChanged(TemperatureSensitivityChanged event) {
		UUID memberId = event.memberId().value();
		offers.markSensitivityChanged(memberId, event.occurredAt());
		offers.findOfferedForUpdate(memberId).ifPresent(stored -> {
			boolean accepted = event.source() == SensitivityChangeSource.CALIBRATION_ACCEPTED
					&& event.current() == stored.offer().suggestedGroup();
			offers.updateStatus(stored.id(),
					accepted ? CalibrationOfferStatus.ACCEPTED : CalibrationOfferStatus.INVALIDATED,
					event.occurredAt());
		});
	}

	/** 탈퇴 (FR-FEL-09): 회원과 연결된 보정 제안 이력·기준 시각을 모두 지운다. */
	@Transactional
	public void onWithdrawn(MemberId memberId) {
		offers.deleteMember(memberId.value());
	}

	private CalibrationOfferRepository.StoredOffer requireOffer(MemberId memberId, UUID offerId) {
		return offers.findForUpdate(offerId, memberId.value())
			.orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "민감도 보정 제안을 찾을 수 없습니다."));
	}

	private static void requireActive(CalibrationOfferRepository.StoredOffer stored, Instant now) {
		CalibrationOfferStatus status = stored.offer().status();
		if (status != CalibrationOfferStatus.OFFERED) {
			throw notActive(status.name(), "이미 처리된 제안입니다.");
		}
		if (!now.isBefore(stored.offer().expiresAt())) {
			throw notActive(CalibrationOfferStatus.EXPIRED.name(), "기간이 지난 제안입니다.");
		}
	}

	private CalibrationOffer reload(UUID offerId) {
		return offers.findById(offerId).orElseThrow().offer();
	}

	private static DomainException notActive(String reason, String message) {
		return new DomainException(ErrorCode.CALIBRATION_OFFER_NOT_ACTIVE, message, Map.of("reason", reason));
	}

}
