package kr.weathertalk.notification.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

import kr.weathertalk.community.CommentCreated;
import kr.weathertalk.member.MemberWithdrawn;
import kr.weathertalk.neighborhood.Neighborhood;
import kr.weathertalk.neighborhood.NeighborhoodApi;
import kr.weathertalk.notification.internal.DeliveryStore.SkipReason;
import kr.weathertalk.notification.internal.Dispatcher.Candidate;
import kr.weathertalk.notification.internal.Dispatcher.QuietPolicy;
import kr.weathertalk.notification.internal.Dispatcher.Round;
import kr.weathertalk.notification.internal.Dispatcher.SkippedTarget;
import kr.weathertalk.notification.internal.config.NotificationProperties;
import kr.weathertalk.shared.Ids.MemberId;
import kr.weathertalk.weather.FieldReportPrecipitationReached;
import kr.weathertalk.weather.PrecipitationStartForecasted;

/**
 * 이벤트 알림 (FR-NOTI-10·11·14·19, 06 §6.4). 이벤트는 발행 저장소에 영속화된 것을 처리하고 실패하면 재처리된다(BR-NOTI-14).
 * 재전달돼도 발송 키 유일성으로 중복 발송하지 않는다(BR-NOTI-10). 발행 후 notification.event.max-delay가 지난 이벤트는 보내지 않고 완료 처리한다.
 * <p>
 * 알림 구독은 바깥 트랜잭션 없이(propagation NOT_SUPPORTED) 돈다 — 발송은 배치마다 따로 커밋해야 해서다(BR-NOTI-11).
 */
@Component
public class EventAlerts {

	private static final Logger log = LoggerFactory.getLogger(EventAlerts.class);

	private final Subscribers subscribers;
	private final Dispatcher dispatcher;
	private final MessageTemplates templates;
	private final DeviceService deviceService;
	private final NeighborhoodApi neighborhoodApi;
	private final NotificationProperties properties;
	private final Clock clock;

	EventAlerts(Subscribers subscribers, Dispatcher dispatcher, MessageTemplates templates, DeviceService deviceService,
			NeighborhoodApi neighborhoodApi, NotificationProperties properties, Clock clock) {
		this.subscribers = subscribers;
		this.dispatcher = dispatcher;
		this.templates = templates;
		this.deviceService = deviceService;
		this.neighborhoodApi = neighborhoodApi;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * 강수 예보 알림 (FR-NOTI-10). 예상 시작 시각이 지났거나 지연 한도를 넘긴 이벤트는 버린다. 심야면 보류했다가 금지 종료 시각에
	 * 예상 시작 시각이 아직 미래면 보낸다. 예보가 사라져도 취소 알림은 없다(Q57).
	 */
	@ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
	void on(PrecipitationStartForecasted event) {
		precipitationForecast(event);
	}

	public void precipitationForecast(PrecipitationStartForecasted event) {
		Instant now = clock.instant();
		if (!now.isBefore(event.expectedStartAt()) || late(event.occurredAt(), now)) {
			log.info("강수 예보 알림 생략(지연 초과): event={}", event.eventId());
			return;
		}
		String code = event.neighborhoodId().value();
		Neighborhood neighborhood = neighborhoodApi.findNeighborhood(event.neighborhoodId()).orElse(null);
		if (neighborhood == null) {
			return;
		}
		Dispatcher.AlertMessage message = templates.precipitationForecast(code, neighborhood.eupmyeondongName(),
				event.precipitationType(), event.precipitationPhrase(), event.expectedStartAt());
		if (message == null) {
			return;
		}
		List<Candidate> candidates = subscribers
			.resolve(subscribers.forNeighborhood(Subscribers.EventColumn.PRECIPITATION_FORECAST, code))
			.stream()
			.filter(r -> code.equals(r.neighborhoodCode()))
			.map(r -> new Candidate(r.recipient(), message))
			.toList();
		Instant deadline = min(event.occurredAt().plus(properties.event().maxDelay()), event.expectedStartAt());
		dispatcher.dispatch(new Round(NotificationKind.PRECIPITATION_FORECAST, event.eventId().toString(), deadline,
				properties.cooldown(), QuietPolicy.HOLD, event.expectedStartAt()), candidates);
	}

	/**
	 * 현장 제보 강수 알림 (FR-NOTI-11, 비·눈). 제보자 본인에게 연결된 기기는 뺀다(BR-NOTI-09). 심야면 버린다.
	 * 문구에 회원이 쓴 글·닉네임을 넣지 않는다.
	 */
	@ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
	void on(FieldReportPrecipitationReached event) {
		fieldReportPrecipitation(event);
	}

	public void fieldReportPrecipitation(FieldReportPrecipitationReached event) {
		Instant now = clock.instant();
		if (late(event.reachedAt(), now)) {
			log.info("현장 제보 강수 알림 생략(지연 초과): event={}", event.eventId());
			return;
		}
		String code = event.neighborhoodId().value();
		Neighborhood neighborhood = neighborhoodApi.findNeighborhood(event.neighborhoodId()).orElse(null);
		if (neighborhood == null) {
			return;
		}
		Dispatcher.AlertMessage message = templates.fieldReportPrecipitation(code, neighborhood.eupmyeondongName(),
				event.precipitationType(), event.reporterCount());
		if (message == null) {
			return;
		}
		Set<UUID> reporters = new LinkedHashSet<>();
		event.reporterIds().stream().map(MemberId::value).forEach(reporters::add);
		List<Candidate> candidates = new ArrayList<>();
		List<SkippedTarget> actors = new ArrayList<>();
		subscribers.resolve(subscribers.forNeighborhood(Subscribers.EventColumn.FIELD_REPORT, code))
			.stream()
			.filter(r -> code.equals(r.neighborhoodCode()))
			.forEach(r -> {
				if (r.memberId() != null && reporters.contains(r.memberId())) {
					actors.add(new SkippedTarget(r.deviceId(), code, SkipReason.ACTOR));
				}
				else {
					candidates.add(new Candidate(r.recipient(), message));
				}
			});
		String roundKey = event.eventId().toString();
		dispatcher.recordSkips(NotificationKind.FIELD_REPORT_PRECIPITATION, roundKey, actors);
		dispatcher.dispatch(new Round(NotificationKind.FIELD_REPORT_PRECIPITATION, roundKey,
				event.reachedAt().plus(properties.event().maxDelay()), properties.cooldown(), QuietPolicy.DROP, null),
				candidates);
	}

	/**
	 * 댓글 알림 (FR-NOTI-19). 수신 회원 = {게시글 작성자} ∪ {상위 댓글 작성자(답글이면)} − {댓글 작성자}. 같은 사람은 1명으로 세고,
	 * 게시글 작성자이기도 한 상위 댓글 작성자에게는 게시글 작성자 문구로 1회만 보낸다. 회원에 연결된 기기에만 간다. 심야면 버린다.
	 * community를 동기 호출하지 않는다 — 그사이 게시글이 지워졌어도 보내고, 앱이 "삭제된 글이에요"를 보여 준다.
	 */
	@ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
	void on(CommentCreated event) {
		comment(event);
	}

	public void comment(CommentCreated event) {
		Instant now = clock.instant();
		if (late(event.occurredAt(), now)) {
			log.info("댓글 알림 생략(지연 초과): event={}", event.eventId());
			return;
		}
		UUID postAuthor = event.postAuthorId() == null ? null : event.postAuthorId().value();
		Set<UUID> recipients = new LinkedHashSet<>();
		if (postAuthor != null) {
			recipients.add(postAuthor);
		}
		if (event.parentCommentAuthorId() != null) {
			recipients.add(event.parentCommentAuthorId().value());
		}
		if (event.commenterId() != null) {
			recipients.remove(event.commenterId().value());
		}
		if (recipients.isEmpty()) {
			return;
		}
		String postId = event.postId().value().toString();
		List<Candidate> candidates = subscribers.commentDevices(recipients)
			.stream()
			.map(device -> new Candidate(device.recipient(),
					templates.comment(postId, device.memberId().equals(postAuthor))))
			.toList();
		dispatcher.dispatch(new Round(NotificationKind.COMMENT, event.commentId().value().toString(),
				event.occurredAt().plus(properties.event().maxDelay()), properties.comment().cooldown(),
				QuietPolicy.DROP, null), candidates);
	}

	/** 회원 탈퇴 (FR-NOTI-14): 기기 연결 해제, 댓글 알림만 끄고 동네별 구독·외출 시각은 유지. 멱등. */
	@ApplicationModuleListener
	void on(MemberWithdrawn event) {
		deviceService.unlinkMember(event.memberId().value());
	}

	private boolean late(Instant occurredAt, Instant now) {
		return occurredAt != null && now.isAfter(occurredAt.plus(properties.event().maxDelay()));
	}

	private static Instant min(Instant a, Instant b) {
		return a.isBefore(b) ? a : b;
	}

}
