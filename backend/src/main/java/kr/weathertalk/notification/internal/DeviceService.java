package kr.weathertalk.notification.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.weathertalk.member.Requester;
import kr.weathertalk.member.TemperatureSensitivity;
import kr.weathertalk.neighborhood.Neighborhood;
import kr.weathertalk.neighborhood.NeighborhoodApi;
import kr.weathertalk.neighborhood.NeighborhoodStatus;
import kr.weathertalk.notification.DepartureTimes;
import kr.weathertalk.notification.DeviceConfirmCommand;
import kr.weathertalk.notification.DeviceConfirmation;
import kr.weathertalk.notification.DeviceCredential;
import kr.weathertalk.notification.DeviceStatus;
import kr.weathertalk.notification.OsNotificationPermission;
import kr.weathertalk.notification.internal.DeviceStore.Device;
import kr.weathertalk.notification.internal.config.NotificationProperties;
import kr.weathertalk.shared.Ids.DeviceId;
import kr.weathertalk.shared.Ids.NeighborhoodId;
import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;
import kr.weathertalk.shared.time.TimeConfig;

/**
 * 기기 (FR-NOTI-01~05, 08, 13, 14, 17, 18, ADR 0008).
 * <ul>
 * <li>기기 확인 하나로 등록·푸시 토큰 갱신·OS 권한·회원 연결 대조를 한다. 로그인/로그아웃 이벤트는 없다(BR-NOTI-04).</li>
 * <li>연결 해제(로그아웃·탈퇴)는 회원 전용 알림(댓글 알림)만 끄고 동네별 구독·외출 시각은 비회원 기기 것으로 남긴다(BR-NOTI-05).
 * 다시 회원과 연결되면 댓글 알림을 기본값(켬)으로 되돌린다(06 §9 Q15 해소).</li>
 * <li>기기 키는 SHA-256 해시만 보관하고, 기기를 바꾸는 모든 요청에서 검증한다(BR-NOTI-15).</li>
 * <li>같은 푸시 토큰은 기기 하나에만 둔다 — 겹치면 기존 기기를 지운다(재설치로 본다, BR-NOTI-03).</li>
 * </ul>
 */
@Service
public class DeviceService {

	private static final Logger log = LoggerFactory.getLogger(DeviceService.class);
	private static final SecureRandom RANDOM = new SecureRandom();

	private final DeviceStore devices;
	private final SubscribedNeighborhoods subscribed;
	private final NeighborhoodApi neighborhoodApi;
	private final NotificationProperties properties;
	private final Clock clock;

	DeviceService(DeviceStore devices, SubscribedNeighborhoods subscribed, NeighborhoodApi neighborhoodApi,
			NotificationProperties properties, Clock clock) {
		this.devices = devices;
		this.subscribed = subscribed;
		this.neighborhoodApi = neighborhoodApi;
		this.properties = properties;
		this.clock = clock;
	}

	/** 기기 확인 (FR-NOTI-01~04). 같은 내용의 반복 호출은 결과가 같다(멱등). */
	@Transactional
	public DeviceConfirmation confirm(DeviceConfirmCommand command, Requester requester) {
		String token = normalizeToken(command.pushToken());
		UUID memberId = requester instanceof Requester.Member member ? member.memberId().value() : null;
		Instant now = clock.instant();
		if (command.deviceId() == null) {
			return register(command, token, memberId, now);
		}
		UUID id = command.deviceId().value();
		Device device = authenticateForUpdate(command.deviceId(), command.deviceKey());
		if (token != null && !token.equals(device.pushToken())) {
			removeOtherDevicesWithToken(token, id);
			devices.updateToken(id, token);
			if (device.status() == DeviceStatus.SUSPENDED) {
				devices.activate(id);
			}
		}
		else if (device.status() == DeviceStatus.SUSPENDED && device.suspendedReason() == DeviceStore.SuspendReason.STALE
				&& device.pushToken() != null) {
			// 장기 미사용으로 멈췄던 기기가 다시 쓰이기 시작했다(토큰 무효로 멈춘 기기는 새 토큰이 와야 되살린다)
			devices.activate(id);
		}
		devices.touch(id, command.platform(), command.osPermission(), now);
		// 연결 대조: 요청자 기준으로 바로잡는다(로그아웃 직후 호출이 빠져도 다음 실행에서 복구, BR-NOTI-04)
		if (memberId != null && !memberId.equals(device.memberId())) {
			subscribed.trackRun(id, () -> devices.link(id, memberId, true));
		}
		else if (memberId == null && device.linked()) {
			subscribed.trackRun(id, () -> devices.link(id, null, false));
		}
		Device after = devices.find(id).orElseThrow();
		return new DeviceConfirmation(new DeviceId(id), null, false, after.status(), after.linked(),
				after.commentEnabled());
	}

	private DeviceConfirmation register(DeviceConfirmCommand command, String token, UUID memberId, Instant now) {
		if (command.platform() == null) {
			throw new DomainException(ErrorCode.INVALID_REQUEST, "기기 등록에는 플랫폼이 필요합니다.");
		}
		UUID id = UUID.randomUUID();
		String key = newKey();
		if (token != null) {
			removeOtherDevicesWithToken(token, id);
		}
		OsNotificationPermission permission = command.osPermission() == null ? OsNotificationPermission.UNKNOWN
				: command.osPermission();
		devices.insert(id, hash(key), command.platform(), token, memberId, permission, memberId != null, now);
		return new DeviceConfirmation(new DeviceId(id), key, true, DeviceStatus.ACTIVE, memberId != null,
				memberId != null);
	}

	/** 기기 키 검증 (BR-NOTI-15). 기기 행을 잠근다(같은 기기의 동시 변경을 줄 세움). */
	@Transactional
	public Device authenticateForUpdate(DeviceId deviceId, String deviceKey) {
		Device device = devices.lock(deviceId.value()).orElseThrow(DeviceService::notFound);
		verifyKey(device, deviceKey);
		return device;
	}

	@Transactional(readOnly = true)
	public Device authenticate(DeviceCredential credential) {
		if (credential == null || credential.deviceId() == null) {
			throw new DomainException(ErrorCode.INVALID_REQUEST, "기기 ID와 기기 키가 필요합니다.");
		}
		Device device = devices.find(credential.deviceId().value()).orElseThrow(DeviceService::notFound);
		verifyKey(device, credential.deviceKey());
		return device;
	}

	/** 비회원 기기 현재 동네 보고 (FR-NOTI-05). 회원 연결 기기의 보고는 무시한다(회원은 neighborhood가 원본). */
	@Transactional
	public void reportNeighborhood(DeviceCredential credential, NeighborhoodId neighborhoodId) {
		Device device = authenticateForUpdate(credential.deviceId(), credential.deviceKey());
		if (device.linked()) {
			return;
		}
		Neighborhood neighborhood = existingNeighborhood(neighborhoodId);
		if (neighborhood.id().value().equals(device.neighborhoodCode())) {
			return;
		}
		subscribed.trackRun(device.id(), () -> devices.setNeighborhood(device.id(), neighborhood.id().value()));
	}

	/** 비회원 알림용 민감도 (FR-NOTI-08). 회원 연결 기기에도 저장은 하되 발송에는 회원 프로필 값을 쓴다. */
	@Transactional
	public void setGuestSensitivity(DeviceCredential credential, TemperatureSensitivity sensitivity) {
		if (sensitivity == null) {
			throw new DomainException(ErrorCode.INVALID_REQUEST, "온도 민감도가 필요합니다.");
		}
		Device device = authenticateForUpdate(credential.deviceId(), credential.deviceKey());
		devices.setGuestSensitivity(device.id(), sensitivity);
	}

	/** 기기 단위 댓글 알림 on/off (FR-NOTI-07). 댓글 알림은 회원 전용이라 회원 연결 기기만 켤 수 있다. */
	@Transactional
	public boolean setCommentAlert(DeviceCredential credential, boolean enabled) {
		Device device = authenticateForUpdate(credential.deviceId(), credential.deviceKey());
		if (enabled && !device.linked()) {
			throw new DomainException(ErrorCode.SIGNUP_REQUIRED, "댓글 알림은 회원 전용입니다.");
		}
		devices.setCommentEnabled(device.id(), enabled);
		return enabled;
	}

	/** 외출 시각 설정 (FR-NOTI-17): 목록 교체. */
	@Transactional
	public DepartureTimes setDepartureTimes(DeviceCredential credential, List<LocalTime> times) {
		Device device = authenticateForUpdate(credential.deviceId(), credential.deviceKey());
		List<LocalTime> validated = validateTimes(times, properties.departure().maxTimes(), "외출 시각");
		devices.replaceDepartureTimes(device.id(), validated);
		return departures(validated);
	}

	/** 외출 시각 조회 (FR-NOTI-18) + 다음 외출 시각(contracts §2.7). */
	@Transactional(readOnly = true)
	public DepartureTimes departureTimes(DeviceCredential credential) {
		Device device = authenticate(credential);
		return departures(devices.departureTimes(device.id()));
	}

	/**
	 * 회원 탈퇴 (FR-NOTI-14): 연결된 모든 기기의 연결을 끊고 댓글 알림만 끈다. 동네별 구독·외출 시각은 비회원 기기 것으로 남는다.
	 * 이미 연결이 없으면 아무것도 하지 않는다(재전달 멱등).
	 */
	@Transactional
	public int unlinkMember(UUID memberId) {
		List<UUID> ids = devices.idsOfMember(memberId);
		for (UUID id : ids) {
			devices.lock(id).ifPresent(device -> {
				if (memberId.equals(device.memberId())) {
					subscribed.trackRun(id, () -> devices.link(id, null, false));
				}
			});
		}
		return ids.size();
	}

	/** 무효 토큰 응답을 받은 기기를 즉시 발송 중지한다(BR-NOTI-12). 그사이 토큰이 바뀌었으면 그대로 둔다. */
	@Transactional
	public void suspendInvalidToken(UUID deviceId, String pushToken) {
		devices.suspend(deviceId, DeviceStore.SuspendReason.INVALID_TOKEN, pushToken, clock.instant());
	}

	/**
	 * 장기 미사용 기기 정리 (FR-NOTI-13): 마지막 확인이 stale-after보다 오래된 활성 기기 → 발송 중지,
	 * 발송 중지 후 purge-after가 지난 기기 → 기기·구독·발송 기록 삭제(구독 동네 감소 반영).
	 */
	@Transactional
	public int[] cleanUpStaleDevices() {
		Instant now = clock.instant();
		int suspended = devices.suspendStale(now.minus(properties.device().staleAfter()), now);
		List<UUID> purgeable = devices.suspendedBefore(now.minus(properties.device().purgeAfter()));
		for (UUID id : purgeable) {
			deleteDevice(id);
		}
		if (suspended > 0 || !purgeable.isEmpty()) {
			log.info("장기 미사용 기기 정리: 발송 중지 {}대, 삭제 {}대", suspended, purgeable.size());
		}
		return new int[] { suspended, purgeable.size() };
	}

	private void removeOtherDevicesWithToken(String token, UUID except) {
		for (UUID other : devices.idsWithToken(token, except)) {
			deleteDevice(other);
		}
	}

	private void deleteDevice(UUID id) {
		devices.lock(id).ifPresent(device -> subscribed.trackRun(id, () -> devices.delete(id)));
	}

	/** 각 외출 시각의 다음 도래 시각: 그 시(時)가 지나지 않았으면 오늘, 지났으면 내일. 다음 외출 시각 = 그중 가장 이른 것. */
	DepartureTimes departures(List<LocalTime> times) {
		ZonedDateTime now = clock.instant().atZone(TimeConfig.SEOUL);
		ZonedDateTime currentHour = now.truncatedTo(ChronoUnit.HOURS);
		List<DepartureTimes.Departure> list = new ArrayList<>();
		for (LocalTime time : times.stream().sorted().toList()) {
			ZonedDateTime at = now.toLocalDate().atTime(time).atZone(TimeConfig.SEOUL);
			if (at.truncatedTo(ChronoUnit.HOURS).isBefore(currentHour)) {
				at = at.plusDays(1);
			}
			list.add(new DepartureTimes.Departure(time, at.toInstant()));
		}
		Instant next = list.stream().map(DepartureTimes.Departure::nextAt).min(Instant::compareTo).orElse(null);
		return new DepartureTimes(List.copyOf(list), next);
	}

	/** 시각 목록 검증: time-unit 단위, 중복 없음, 최대 개수. null은 빈 목록. */
	List<LocalTime> validateTimes(List<LocalTime> times, int maxCount, String label) {
		List<LocalTime> list = times == null ? List.of() : times;
		long unitSeconds = properties.daily().timeUnit().toSeconds();
		List<String> violations = new ArrayList<>();
		if (list.stream().anyMatch(t -> t == null || t.toSecondOfDay() % unitSeconds != 0)) {
			violations.add(label + "은 " + properties.daily().timeUnit().toMinutes() + "분 단위여야 합니다.");
		}
		if (list.stream().distinct().count() != list.size()) {
			violations.add(label + "이 중복됐습니다.");
		}
		if (list.size() > maxCount) {
			violations.add(label + "은 최대 " + maxCount + "개입니다.");
		}
		if (!violations.isEmpty()) {
			throw new DomainException(ErrorCode.INVALID_REQUEST, String.join(" ", violations),
					Map.of("violations", violations));
		}
		return list.stream().sorted().toList();
	}

	Neighborhood existingNeighborhood(NeighborhoodId id) {
		if (id == null || id.value() == null || id.value().isBlank()) {
			throw new DomainException(ErrorCode.INVALID_REQUEST, "동네 ID가 필요합니다.");
		}
		return neighborhoodApi.findNeighborhood(id)
			.filter(n -> n.status() == NeighborhoodStatus.VALID)
			.orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "없는 동네입니다.", Map.of("reason",
					"NEIGHBORHOOD_NOT_FOUND")));
	}

	private String normalizeToken(String token) {
		if (token == null || token.isBlank()) {
			return null;
		}
		String value = token.strip();
		if (value.length() > properties.device().pushTokenMaxLength()) {
			throw new DomainException(ErrorCode.INVALID_REQUEST, "푸시 토큰이 너무 깁니다.");
		}
		return value;
	}

	private static void verifyKey(Device device, String deviceKey) {
		if (deviceKey == null || deviceKey.isBlank() || !MessageDigest.isEqual(
				device.keyHash().getBytes(StandardCharsets.US_ASCII), hash(deviceKey).getBytes(StandardCharsets.US_ASCII))) {
			throw new DomainException(ErrorCode.DEVICE_KEY_MISMATCH, "기기 키가 맞지 않습니다.");
		}
	}

	private static DomainException notFound() {
		return new DomainException(ErrorCode.NOT_FOUND, "등록되지 않은 기기입니다. 기기 확인으로 다시 등록해 주세요.",
				Map.of("reason", "DEVICE_NOT_FOUND"));
	}

	private static String newKey() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	static String hash(String key) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

}
