package kr.weathertalk.notification.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import kr.weathertalk.member.MemberApi;
import kr.weathertalk.member.MemberProfile;
import kr.weathertalk.member.TemperatureSensitivity;
import kr.weathertalk.neighborhood.CurrentNeighborhood;
import kr.weathertalk.neighborhood.Neighborhood;
import kr.weathertalk.neighborhood.NeighborhoodApi;
import kr.weathertalk.notification.DeviceCredential;
import kr.weathertalk.notification.internal.DeviceStore.Device;
import kr.weathertalk.notification.internal.Dispatcher.Candidate;
import kr.weathertalk.notification.internal.Dispatcher.QuietPolicy;
import kr.weathertalk.notification.internal.Dispatcher.Round;
import kr.weathertalk.notification.internal.config.NotificationProperties;
import kr.weathertalk.shared.Ids.MemberId;
import kr.weathertalk.shared.Ids.NeighborhoodId;
import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;
import kr.weathertalk.shared.profile.DevelopmentOnly;
import kr.weathertalk.weather.PrecipitationType;

/**
 * 테스트 발송 (FR-NOTI-15). 운영 프로파일에는 등록되지 않고(@DevelopmentOnly + notification.test.enabled), 지정한 기기 1개에
 * 4종 문구 중 하나를 즉시 보낸다. 외부 기상 API를 강제 호출하지 않고 weather 조회 인터페이스만 쓴다. 기기당 notification.test.rate-limit.
 */
@Service
@DevelopmentOnly
@ConditionalOnProperty(name = "notification.test.enabled", havingValue = "true")
public class TestSendService {

	private final DeviceService devices;
	private final DailyWeatherAlerts daily;
	private final MessageTemplates templates;
	private final Dispatcher dispatcher;
	private final NeighborhoodApi neighborhoodApi;
	private final MemberApi memberApi;
	private final StringRedisTemplate redis;
	private final NotificationProperties properties;
	private final Clock clock;

	TestSendService(DeviceService devices, DailyWeatherAlerts daily, MessageTemplates templates, Dispatcher dispatcher,
			NeighborhoodApi neighborhoodApi, MemberApi memberApi, StringRedisTemplate redis,
			NotificationProperties properties, Clock clock) {
		this.devices = devices;
		this.daily = daily;
		this.templates = templates;
		this.dispatcher = dispatcher;
		this.neighborhoodApi = neighborhoodApi;
		this.memberApi = memberApi;
		this.redis = redis;
		this.properties = properties;
		this.clock = clock;
	}

	public Dispatcher.Report send(DeviceCredential credential, NotificationKind kind, NeighborhoodId neighborhoodId) {
		Device device = devices.authenticate(credential);
		limit(device.id());
		if (device.pushToken() == null) {
			throw new DomainException(ErrorCode.INVALID_REQUEST, "푸시 토큰이 없는 기기입니다.");
		}
		Neighborhood neighborhood = neighborhood(device, neighborhoodId);
		String code = neighborhood.id().value();
		String name = neighborhood.eupmyeondongName();
		Instant now = clock.instant();
		Dispatcher.AlertMessage message = switch (kind) {
			case DAILY_WEATHER -> daily.preview(neighborhood, sensitivity(device))
				.orElseThrow(() -> new DomainException(ErrorCode.TEMPORARILY_UNAVAILABLE, "이 동네의 날씨를 아직 가져오지 못했습니다."));
			case PRECIPITATION_FORECAST -> templates.precipitationForecast(code, name, PrecipitationType.RAIN, "약한 비",
					now.plusSeconds(3600));
			case FIELD_REPORT_PRECIPITATION -> templates.fieldReportPrecipitation(code, name, PrecipitationType.RAIN, 3);
			case COMMENT -> templates.comment(UUID.randomUUID().toString(), true);
		};
		return dispatcher.dispatch(new Round(kind, "test:" + UUID.randomUUID(), now.plusSeconds(300), null,
				QuietPolicy.EXEMPT, null),
				List.of(new Candidate(new Dispatcher.Recipient(device.id(), device.pushToken(), device.platform()),
						message)));
	}

	private void limit(UUID deviceId) {
		String key = "noti:test-send:" + deviceId;
		Long count = redis.opsForValue().increment(key);
		if (count != null && count == 1) {
			redis.expire(key, properties.test().rateLimit().period());
		}
		if (count != null && count > properties.test().rateLimit().maxCount()) {
			throw new DomainException(ErrorCode.TEST_SEND_RATE_LIMITED, "테스트 발송 한도를 넘었습니다.");
		}
	}

	private Neighborhood neighborhood(Device device, NeighborhoodId requested) {
		if (requested != null) {
			return devices.existingNeighborhood(requested);
		}
		String code = device.linked()
				? neighborhoodApi.findCurrentNeighborhood(new MemberId(device.memberId()))
					.map(CurrentNeighborhood::neighborhood)
					.map(n -> n.id().value())
					.orElse(null)
				: device.neighborhoodCode();
		if (code == null) {
			throw new DomainException(ErrorCode.INVALID_REQUEST, "보낼 동네를 정해 주세요(neighborhoodId).",
					Map.of("reason", "NEIGHBORHOOD_UNKNOWN"));
		}
		return devices.existingNeighborhood(new NeighborhoodId(code));
	}

	private TemperatureSensitivity sensitivity(Device device) {
		if (!device.linked()) {
			return device.guestSensitivity();
		}
		return memberApi.findProfile(new MemberId(device.memberId()))
			.map(MemberProfile::temperatureSensitivity)
			.orElse(TemperatureSensitivity.NORMAL);
	}

}
