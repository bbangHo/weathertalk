package kr.weathertalk.notification.internal;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.weathertalk.notification.internal.config.NotificationProperties;

/**
 * 정리 작업: 발송 기록 보존 기간 경과분 삭제(FR-NOTI-16, notification.delivery-log.retention), 만료된 쿨다운 행 삭제,
 * 장기 미사용 기기 정리(FR-NOTI-13), 알림 구독 동네 대조(FR-NBR-10 보정).
 * 쿨다운 기간(최대 몇 시간)은 보존 기간(30일)보다 훨씬 짧아 정리가 쿨다운 판정에 영향을 주지 않는다.
 */
@Service
public class NotificationMaintenance {

	private static final Logger log = LoggerFactory.getLogger(NotificationMaintenance.class);

	private final DeliveryStore deliveries;
	private final CooldownStore cooldowns;
	private final DeviceService devices;
	private final SubscribedNeighborhoods subscribed;
	private final NotificationProperties properties;
	private final Clock clock;

	NotificationMaintenance(DeliveryStore deliveries, CooldownStore cooldowns, DeviceService devices,
			SubscribedNeighborhoods subscribed, NotificationProperties properties, Clock clock) {
		this.deliveries = deliveries;
		this.cooldowns = cooldowns;
		this.devices = devices;
		this.subscribed = subscribed;
		this.properties = properties;
		this.clock = clock;
	}

	@Transactional
	public int purgeDeliveryLog() {
		Instant now = clock.instant();
		cooldowns.deleteExpired(now);
		int deleted = deliveries.deleteCreatedBefore(now.minus(properties.deliveryLog().retention()));
		if (deleted > 0) {
			log.info("발송 기록 정리: {}건 삭제", deleted);
		}
		return deleted;
	}

	public int[] cleanUpStaleDevices() {
		return devices.cleanUpStaleDevices();
	}

	public int reconcileSubscribedNeighborhoods() {
		return subscribed.reconcile();
	}

}
