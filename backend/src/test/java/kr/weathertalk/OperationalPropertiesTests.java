package kr.weathertalk;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.util.unit.DataSize;

import kr.weathertalk.community.internal.config.CommunityProperties;
import kr.weathertalk.community.internal.config.CommunityPropertiesConfig;
import kr.weathertalk.feeling.internal.config.FeelingProperties;
import kr.weathertalk.feeling.internal.config.FeelingPropertiesConfig;
import kr.weathertalk.home.internal.config.HomeProperties;
import kr.weathertalk.home.internal.config.HomePropertiesConfig;
import kr.weathertalk.member.internal.config.MemberProperties;
import kr.weathertalk.member.internal.config.MemberPropertiesConfig;
import kr.weathertalk.neighborhood.internal.config.NeighborhoodProperties;
import kr.weathertalk.neighborhood.internal.config.NeighborhoodPropertiesConfig;
import kr.weathertalk.notification.internal.config.NotificationProperties;
import kr.weathertalk.notification.internal.config.NotificationPropertiesConfig;
import kr.weathertalk.outfit.internal.config.OutfitProperties;
import kr.weathertalk.outfit.internal.config.OutfitPropertiesConfig;
import kr.weathertalk.weather.internal.config.WeatherProperties;
import kr.weathertalk.weather.internal.config.WeatherPropertiesConfig;

/**
 * 운영 파라미터 외부화: application.yml 초기값이 README·각 모듈 문서 §7 값으로 바인딩되고, 잘못된 값이면 기동이 실패한다.
 */
class OperationalPropertiesTests {

	final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withInitializer(new ConfigDataApplicationContextInitializer())
		.withUserConfiguration(MemberPropertiesConfig.class, NeighborhoodPropertiesConfig.class,
				WeatherPropertiesConfig.class, FeelingPropertiesConfig.class, OutfitPropertiesConfig.class,
				CommunityPropertiesConfig.class, NotificationPropertiesConfig.class, HomePropertiesConfig.class);

	@Test
	void bindsReadmeInitialValues() {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			FeelingProperties feeling = context.getBean(FeelingProperties.class);
			assertThat(feeling.neighborhoodWindow()).isEqualTo(Duration.ofHours(2));
			assertThat(feeling.minGroupRecords()).isEqualTo(5);
			assertThat(feeling.recordInterval()).isEqualTo(Duration.ofHours(1));
			// BR-FEL-18 고정 기준표, 체감 매핑 기능 플래그(M6 전까지 꺼짐)
			assertThat(feeling.referenceTable().normal()).containsExactly(32.0, 27.0, 23.0, 17.0, 13.0, 8.0);
			assertThat(feeling.mapping().enabled()).isFalse();
			// 옷차림 매핑표 7단계
			assertThat(context.getBean(OutfitProperties.class).table().levels()).hasSize(7);

			WeatherProperties weather = context.getBean(WeatherProperties.class);
			assertThat(weather.fieldReport().rainThreshold()).isEqualTo(3);
			assertThat(weather.fieldReport().rainWindow()).isEqualTo(Duration.ofMinutes(30));
			assertThat(weather.midTermDays()).isEqualTo(10);
			assertThat(weather.dayNight().dayStart()).isEqualTo(LocalTime.of(6, 0));

			NotificationProperties notification = context.getBean(NotificationProperties.class);
			assertThat(notification.cooldown()).isEqualTo(Duration.ofHours(3));
			assertThat(notification.quietHours().start()).isEqualTo(LocalTime.of(22, 0));
			assertThat(notification.quietHours().end()).isEqualTo(LocalTime.of(7, 0));
			assertThat(notification.test().enabled()).isTrue();

			CommunityProperties community = context.getBean(CommunityProperties.class);
			assertThat(community.maxImages()).isEqualTo(4);
			assertThat(community.image().maxFileSize()).isEqualTo(DataSize.ofMegabytes(10));

			assertThat(context).hasSingleBean(MemberProperties.class)
				.hasSingleBean(NeighborhoodProperties.class)
				.hasSingleBean(OutfitProperties.class)
				.hasSingleBean(HomeProperties.class);
		});
	}

	@Test
	void prodProfileDisablesTestNotifications() {
		runner.withPropertyValues("spring.profiles.active=prod", "member.token.secret=operations-provided-secret-0123456789abcdef")
			.run(context -> assertThat(context.getBean(NotificationProperties.class).test().enabled()).isFalse());
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"feeling.min-group-records=0",
			"feeling.mapping.schedule=매일",
			"weather.retention.observation=24h",
			"weather.rain-phrase.rain-heavy=1",
			"member.nickname.min-length=20",
			"community.feed.page-size=100",
			"notification.send.batch-size=1000",
			"notification.timezone=UTC",
			"home.section-timeout=" })
	void invalidValueFailsStartup(String invalid) {
		runner.withPropertyValues(invalid).run(context -> assertThat(context).hasFailed());
	}

}
