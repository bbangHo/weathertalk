package kr.weathertalk.notification.internal.push;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import kr.weathertalk.notification.internal.config.NotificationProperties;
import kr.weathertalk.notification.internal.config.NotificationPushProperties;

/**
 * 푸시 발송기 등록: Firebase 서비스 계정 키가 있으면 FCM HTTP v1, 없거나 읽을 수 없으면 발송 비활성(기동은 정상, 경고 로그).
 */
@Configuration(proxyBeanMethods = false)
class PushSenderConfig {

	private static final Logger log = LoggerFactory.getLogger(PushSenderConfig.class);

	@Bean
	PushSender pushSender(NotificationPushProperties push, NotificationProperties properties, Clock clock) {
		return create(push.fcm(), properties, clock);
	}

	static PushSender create(NotificationPushProperties.Fcm fcm, NotificationProperties properties, Clock clock) {
		String path = fcm.credentialsPath();
		if (path == null || path.isBlank()) {
			log.warn("FIREBASE_CREDENTIALS_PATH가 비어 있어 푸시 발송이 꺼져 있습니다(알림은 '생략(발송 비활성)'으로 기록).");
			return new DisabledPushSender();
		}
		try {
			return new FcmPushSender(FcmServiceAccount.load(path.strip()), fcm.baseUrl(), fcm.timeout(),
					properties.send().invalidTokenCodes(), clock);
		}
		catch (IllegalStateException e) {
			log.warn("Firebase 서비스 계정 키를 읽지 못해 푸시 발송이 꺼져 있습니다: {}", e.getMessage());
			return new DisabledPushSender();
		}
	}

}
