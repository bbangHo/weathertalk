package kr.weathertalk.notification.internal;

import java.util.concurrent.atomic.AtomicReference;

import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import kr.weathertalk.notification.internal.config.NotificationProperties;
import kr.weathertalk.notification.internal.config.NotificationPropertiesConfig;

/** 단위 테스트용: application.yml 초기값 그대로의 {@link NotificationProperties}. */
public final class NotificationPropertiesFixture {

	private static final AtomicReference<NotificationProperties> DEFAULTS = new AtomicReference<>();

	private NotificationPropertiesFixture() {
	}

	public static NotificationProperties defaults() {
		if (DEFAULTS.get() == null) {
			new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
				.withUserConfiguration(NotificationPropertiesConfig.class)
				.run(context -> DEFAULTS.set(context.getBean(NotificationProperties.class)));
		}
		return DEFAULTS.get();
	}

}
