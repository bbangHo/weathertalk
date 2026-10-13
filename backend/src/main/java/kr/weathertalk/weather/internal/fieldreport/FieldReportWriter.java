package kr.weathertalk.weather.internal.fieldreport;

import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import kr.weathertalk.shared.Ids.MemberId;
import kr.weathertalk.shared.Ids.NeighborhoodId;
import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;
import kr.weathertalk.shared.time.TimeConfig;
import kr.weathertalk.weather.FieldReportAxis;
import kr.weathertalk.weather.FieldReportKind;
import kr.weathertalk.weather.FieldReportPrecipitationReached;
import kr.weathertalk.weather.PrecipitationType;
import kr.weathertalk.weather.internal.config.WeatherProperties;
import kr.weathertalk.weather.internal.fieldreport.FieldReportRepository.StoredFieldReport;

/**
 * 현장 제보 쓰기·취소와 현장 제보 강수 기준 도달 판정 (FR-WEA-04·14, BR-WEA-16·16a·17).
 * <p>
 * 동시성: 같은 회원의 쓰기는 회원 단위 잠금으로, 기준 도달 판정·발행 기록 갱신은 (동네, 강수 형태) 단위 잠금으로 한 줄로 세운다.
 * 잠금을 얻은 뒤의 집계 문장은 앞서 커밋된 제보를 모두 보므로, 동시에 여러 제보가 들어와도 기준을 넘긴 마지막 쓰기 한 건만
 * 발행하고 발행 기록을 남긴다(정확히 1회). 이벤트는 같은 트랜잭션에서 발행해 이벤트 발행 저장소에 함께 영속화된다(03 §3.9).
 */
@Component
class FieldReportWriter {

	private final FieldReportRepository reports;
	private final ApplicationEventPublisher events;
	private final WeatherProperties properties;
	private final Clock clock;

	FieldReportWriter(FieldReportRepository reports, ApplicationEventPublisher events, WeatherProperties properties,
			Clock clock) {
		this.reports = reports;
		this.events = events;
		this.properties = properties;
		this.clock = clock;
	}

	@Transactional
	StoredFieldReport write(MemberId memberId, NeighborhoodId neighborhoodId, FieldReportKind kind) {
		Instant now = clock.instant();
		UUID member = memberId.value();
		reports.lock("weather.field-report.member:" + member);
		WeatherProperties.FieldReport rule = properties.fieldReport();

		ZonedDateTime dayStart = now.atZone(TimeConfig.SEOUL).toLocalDate().atStartOfDay(TimeConfig.SEOUL);
		if (reports.countByMemberBetween(member, dayStart.toInstant(), dayStart.plusDays(1).toInstant()) >= rule
			.dailyLimit()) {
			throw new DomainException(ErrorCode.FIELD_REPORT_DAILY_LIMIT_EXCEEDED, "오늘은 더 제보할 수 없습니다.",
					Map.of("dailyLimit", rule.dailyLimit()));
		}

		// 같은 동네·같은 축 min-interval 안의 본인 제보는 새 제보로 대체한다(최신 관찰이 이긴다)
		reports.findActiveSince(member, neighborhoodId.value(), kind.axis(), now.minus(rule.minInterval()))
			.ifPresent(previous -> reports.markReplaced(previous.id(), now));
		StoredFieldReport saved = new StoredFieldReport(UUID.randomUUID(), neighborhoodId.value(), member, kind.axis(),
				kind, now, null, null);
		reports.insert(saved);

		if (kind.precipitationType() != null) {
			judgePrecipitation(neighborhoodId, kind.precipitationType(), now);
		}
		return saved;
	}

	/**
	 * BR-WEA-17: rain-window 안 하늘·강수 축 최신 제보(취소·대체 제외)가 그 강수 형태인 서로 다른 제보자 수 ≥ rain-threshold이면
	 * 발행한다. 비와 눈은 따로 센다. 그 (동네, 강수 형태)의 마지막 발행이 rain-suppress 이내면 발행하지 않는다.
	 */
	private void judgePrecipitation(NeighborhoodId neighborhoodId, PrecipitationType type, Instant now) {
		WeatherProperties.FieldReport rule = properties.fieldReport();
		reports.lock("weather.field-report.precip:" + neighborhoodId.value() + ":" + type.name());
		boolean suppressed = reports.lastPrecipPublishedAt(neighborhoodId.value(), type)
			.map(last -> !last.plus(rule.rainSuppress()).isBefore(now))
			.orElse(false);
		if (suppressed) {
			return;
		}
		List<StoredFieldReport> reporters = FieldReportAggregator
			.latestPerReporter(reports.findActive(neighborhoodId.value(), now.minus(rule.rainWindow()), now),
					FieldReportAxis.SKY_PRECIPITATION)
			.stream()
			.filter(r -> r.kind().precipitationType() == type)
			.toList();
		if (reporters.size() < rule.rainThreshold()) {
			return;
		}
		reports.savePrecipPublication(neighborhoodId.value(), type, now, reporters.size());
		List<MemberId> memberIds = reporters.stream()
			.map(StoredFieldReport::memberId)
			.filter(Objects::nonNull)
			.map(MemberId::new)
			.toList();
		events.publishEvent(new FieldReportPrecipitationReached(UUID.randomUUID(), neighborhoodId, type, now,
				reporters.size(), memberIds));
	}

	/**
	 * FR-WEA-14: 본인 제보만(아니면 '없음'과 같은 응답), 이미 취소됐으면 그대로 성공, 제보 시각 + cancel-window가 지났으면 거부.
	 * 이미 발행된 기준 도달 이벤트와 발행 기록은 그대로 둔다(Q56).
	 */
	@Transactional
	StoredFieldReport cancel(MemberId memberId, UUID reportId) {
		Instant now = clock.instant();
		StoredFieldReport report = reports.findForUpdate(reportId)
			.filter(r -> memberId.value().equals(r.memberId()))
			.orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "현장 제보를 찾을 수 없습니다."));
		if (report.canceledAt() != null) {
			return report;
		}
		if (now.isAfter(report.reportedAt().plus(properties.fieldReport().cancelWindow()))) {
			throw new DomainException(ErrorCode.FIELD_REPORT_CANCEL_WINDOW_PASSED, "제보 후 취소할 수 있는 시간이 지났습니다.");
		}
		reports.markCanceled(reportId, now);
		return new StoredFieldReport(report.id(), report.neighborhoodCode(), report.memberId(), report.axis(),
				report.kind(), report.reportedAt(), report.replacedAt(), now);
	}

}
