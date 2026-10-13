package kr.weathertalk.notification.internal;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import kr.weathertalk.notification.internal.config.NotificationProperties;
import kr.weathertalk.shared.time.TimeConfig;

/**
 * 심야 금지 시간 (BR-NOTI-08, notification.quiet-hours, Asia/Seoul). 자정을 넘기는 구간(22:00~07:00)을 지원한다.
 * 정기 날씨 알림에는 적용하지 않는다(ADR 0013) — 판단은 호출자가 종류별로 한다.
 */
@Component
public class QuietHours {

	private final LocalTime start;
	private final LocalTime end;

	@Autowired
	QuietHours(NotificationProperties properties) {
		this.start = properties.quietHours().start();
		this.end = properties.quietHours().end();
	}

	public boolean contains(Instant at) {
		LocalTime time = at.atZone(TimeConfig.SEOUL).toLocalTime();
		if (start.equals(end)) {
			return false;
		}
		if (start.isBefore(end)) {
			return !time.isBefore(start) && time.isBefore(end);
		}
		return !time.isBefore(start) || time.isBefore(end);
	}

	/** 지금이 금지 시간이면 그 금지가 끝나는 시각. 아니면 그대로 at. */
	public Instant endOf(Instant at) {
		if (!contains(at)) {
			return at;
		}
		ZonedDateTime local = at.atZone(TimeConfig.SEOUL);
		ZonedDateTime endToday = local.toLocalDate().atTime(end).atZone(TimeConfig.SEOUL);
		return (endToday.isAfter(local) ? endToday : endToday.plusDays(1)).toInstant();
	}

	static QuietHours of(LocalTime start, LocalTime end) {
		return new QuietHours(start, end);
	}

	private QuietHours(LocalTime start, LocalTime end) {
		this.start = start;
		this.end = end;
	}

}
