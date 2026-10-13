package kr.weathertalk.notification.internal;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.weathertalk.member.TemperatureSensitivity;
import kr.weathertalk.neighborhood.CurrentNeighborhood;
import kr.weathertalk.neighborhood.Neighborhood;
import kr.weathertalk.neighborhood.NeighborhoodApi;
import kr.weathertalk.notification.DeviceCredential;
import kr.weathertalk.notification.OsNotificationPermission;
import kr.weathertalk.notification.internal.DeviceStore.Device;
import kr.weathertalk.notification.internal.SubscriptionStore.Subscription;
import kr.weathertalk.notification.internal.SubscriptionStore.TargetType;
import kr.weathertalk.notification.internal.config.NotificationProperties;
import kr.weathertalk.shared.Ids.MemberId;
import kr.weathertalk.shared.Ids.NeighborhoodId;
import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;

/**
 * 알림 구독 조회·생성·변경·삭제 (FR-NOTI-06·07, §3.2 불변식). 서버가 검증한다(legacy 05-#13):
 * 시각 단위·개수, 기기당 구독 수, 지정 동네 존재, 같은 구독 동네 중복 없음. 세 종류가 모두 꺼지면 구독을 지운다(본문 없는 알림 경로 제거).
 * 정기 날씨 알림을 켜도 시각이 0개면 꺼진 것으로 저장한다(모두 끄기 가능, legacy 07-#13).
 */
@Service
public class SubscriptionService {

	/** 구독 동네 지정. FOLLOW_CURRENT면 neighborhoodId는 비운다. */
	public record Target(TargetType type, NeighborhoodId neighborhoodId) {
	}

	/**
	 * @param dailyTimes null이면 notification.daily.default-time 하나(정기 날씨 알림을 켤 때만)
	 */
	public record Command(Target target, boolean dailyEnabled, List<LocalTime> dailyTimes,
			boolean precipitationForecastEnabled, boolean fieldReportPrecipitationEnabled) {
	}

	/**
	 * @param neighborhoodId 지정 동네(지정 동네 구독만)
	 * @param resolvedNeighborhoodId 지금 해석되는 동네(현재 동네 따라가기는 회원의 현재 동네 또는 기기 현재 동네, 모르면 null)
	 */
	public record View(UUID id, TargetType targetType, String neighborhoodId, String neighborhoodName,
			String resolvedNeighborhoodId, String resolvedNeighborhoodName, boolean dailyEnabled,
			List<LocalTime> dailyTimes, boolean precipitationForecastEnabled, boolean fieldReportPrecipitationEnabled,
			Instant consentedAt) {
	}

	/** 기기 단위 설정과 동네별 구독 목록 (FR-NOTI-06). */
	public record Settings(boolean linkedToMember, boolean commentAlertEnabled, TemperatureSensitivity guestSensitivity,
			String deviceNeighborhoodId, List<View> subscriptions) {
	}

	private final DeviceService deviceService;
	private final SubscriptionStore subscriptions;
	private final SubscribedNeighborhoods subscribed;
	private final NeighborhoodApi neighborhoodApi;
	private final NotificationProperties properties;
	private final Clock clock;

	SubscriptionService(DeviceService deviceService, SubscriptionStore subscriptions, SubscribedNeighborhoods subscribed,
			NeighborhoodApi neighborhoodApi, NotificationProperties properties, Clock clock) {
		this.deviceService = deviceService;
		this.subscriptions = subscriptions;
		this.subscribed = subscribed;
		this.neighborhoodApi = neighborhoodApi;
		this.properties = properties;
		this.clock = clock;
	}

	/** 알림 구독 조회 (FR-NOTI-06). 푸시 토큰이 아니라 기기 ID로 조회한다. 구독이 없으면 빈 목록(오류 아님). */
	@Transactional(readOnly = true)
	public Settings settings(DeviceCredential credential) {
		Device device = deviceService.authenticate(credential);
		List<Subscription> list = subscriptions.ofDevice(device.id());
		String followed = device.linked() ? memberCurrent(device.memberId()) : device.neighborhoodCode();
		List<String> codes = new ArrayList<>();
		list.forEach(s -> codes.add(s.neighborhoodCode()));
		codes.add(followed);
		Map<NeighborhoodId, Neighborhood> names = neighborhoodApi.findNeighborhoods(
				codes.stream().filter(c -> c != null).distinct().map(NeighborhoodId::new).toList());
		List<View> views = list.stream().map(s -> {
			String resolved = s.targetType() == TargetType.FIXED ? s.neighborhoodCode() : followed;
			return new View(s.id(), s.targetType(), s.neighborhoodCode(), name(names, s.neighborhoodCode()), resolved,
					name(names, resolved), s.dailyEnabled(), s.dailyTimes(), s.precipitationForecastEnabled(),
					s.fieldReportEnabled(), s.consentedAt());
		}).toList();
		return new Settings(device.linked(), device.commentEnabled(), device.linked() ? null : device.guestSensitivity(),
				device.neighborhoodCode(), views);
	}

	/**
	 * 알림 구독 생성·변경 (FR-NOTI-07): (기기, 구독 동네)가 있으면 바꾸고 없으면 만든다. 세 종류가 모두 꺼지면 지운다.
	 * 구독 동네가 생기거나 사라지면 neighborhood에 알림 구독 동네 증감을 알린다(FR-NBR-10).
	 *
	 * @return 저장된 구독. 모두 꺼져 지웠으면 빈 값
	 */
	@Transactional
	public Optional<View> upsert(DeviceCredential credential, Command command) {
		Device device = deviceService.authenticateForUpdate(credential.deviceId(), credential.deviceKey());
		Target target = validateTarget(command.target());
		List<LocalTime> times = command.dailyEnabled() && command.dailyTimes() == null
				? List.of(properties.daily().defaultTime())
				: deviceService.validateTimes(command.dailyTimes(), properties.daily().maxTimes(), "정기 알림 시각");
		boolean daily = command.dailyEnabled() && !times.isEmpty();
		boolean anyOn = daily || command.precipitationForecastEnabled() || command.fieldReportPrecipitationEnabled();
		String code = target.type() == TargetType.FIXED ? target.neighborhoodId().value() : null;
		Optional<Subscription> existing = subscriptions.find(device.id(), target.type(), code);
		Instant now = clock.instant();
		if (!anyOn) {
			existing.ifPresent(s -> subscribed.trackRun(device.id(), () -> subscriptions.delete(s.id())));
			return Optional.empty();
		}
		// 수신 동의 근거: OS 알림 권한이 허용된 상태에서 처음 켠 시각(ADR 0013)
		Instant consentedAt = existing.map(Subscription::consentedAt).orElse(null);
		if (consentedAt == null && device.osPermission() == OsNotificationPermission.GRANTED) {
			consentedAt = now;
		}
		Instant consent = consentedAt;
		UUID id = existing.map(Subscription::id).orElseGet(UUID::randomUUID);
		if (existing.isEmpty() && subscriptions.ofDevice(device.id()).size() >= properties.subscription().maxPerDevice()) {
			throw new DomainException(ErrorCode.INVALID_REQUEST,
					"알림 구독은 기기당 최대 " + properties.subscription().maxPerDevice() + "개입니다.",
					Map.of("violations", List.of("SUBSCRIPTION_LIMIT")));
		}
		subscribed.trackRun(device.id(), () -> {
			if (existing.isPresent()) {
				subscriptions.update(id, daily, command.precipitationForecastEnabled(),
						command.fieldReportPrecipitationEnabled(), consent, now);
			}
			else {
				subscriptions.insert(id, device.id(), target.type(), code, daily,
						command.precipitationForecastEnabled(), command.fieldReportPrecipitationEnabled(), consent, now);
			}
			subscriptions.replaceDailyTimes(id, times);
		});
		return settings(credential).subscriptions().stream().filter(v -> v.id().equals(id)).findFirst();
	}

	/** 알림 구독 삭제. 없으면 그대로 성공(멱등). */
	@Transactional
	public void delete(DeviceCredential credential, Target target) {
		Device device = deviceService.authenticateForUpdate(credential.deviceId(), credential.deviceKey());
		Target valid = target.type() == TargetType.FIXED && target.neighborhoodId() != null ? target
				: validateTarget(target);
		String code = valid.type() == TargetType.FIXED ? valid.neighborhoodId().value() : null;
		subscriptions.find(device.id(), valid.type(), code)
			.ifPresent(s -> subscribed.trackRun(device.id(), () -> subscriptions.delete(s.id())));
	}

	private Target validateTarget(Target target) {
		if (target == null || target.type() == null) {
			throw new DomainException(ErrorCode.INVALID_REQUEST, "구독 동네를 정해 주세요.");
		}
		if (target.type() == TargetType.FOLLOW_CURRENT) {
			return new Target(TargetType.FOLLOW_CURRENT, null);
		}
		Neighborhood neighborhood = deviceService.existingNeighborhood(target.neighborhoodId());
		return new Target(TargetType.FIXED, neighborhood.id());
	}

	private String memberCurrent(UUID memberId) {
		return neighborhoodApi.findCurrentNeighborhood(new MemberId(memberId))
			.map(CurrentNeighborhood::neighborhood)
			.map(n -> n.id().value())
			.orElse(null);
	}

	private static String name(Map<NeighborhoodId, Neighborhood> names, String code) {
		if (code == null) {
			return null;
		}
		Neighborhood n = names.get(new NeighborhoodId(code));
		return n == null ? null : n.eupmyeondongName();
	}

}
