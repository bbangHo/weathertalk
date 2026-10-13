package kr.weathertalk.outfit.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import kr.weathertalk.outfit.DepartureCondition;
import kr.weathertalk.outfit.DepartureOutfitRecommendation;
import kr.weathertalk.outfit.OutfitRecommendation;
import kr.weathertalk.outfit.Precipitation;
import kr.weathertalk.outfit.internal.config.OutfitProperties;
import kr.weathertalk.outfit.internal.config.OutfitPropertiesConfig;
import kr.weathertalk.shared.error.DomainException;

/**
 * 옷 추천 (FR-OUT-01·02, BR-OUT-01~04). application.yml의 옷차림 매핑표(outfit.table)를 그대로 읽어 검증한다.
 */
class OutfitServiceTests {

	final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withInitializer(new ConfigDataApplicationContextInitializer())
		.withUserConfiguration(OutfitPropertiesConfig.class);

	static final Instant MORNING = Instant.parse("2026-10-13T23:00:00Z");
	static final Instant EVENING = Instant.parse("2026-10-14T09:00:00Z");

	private OutfitService service() {
		OutfitService[] holder = new OutfitService[1];
		runner.run(context -> holder[0] = new OutfitService(context.getBean(OutfitProperties.class)));
		return holder[0];
	}

	@Test
	void tableCoversAllSevenLevelsAndAddsPrecipitationAccessoriesOnly() {
		OutfitService service = service();
		for (int level = 1; level <= 7; level++) {
			OutfitRecommendation r = service.recommend(level, null);
			assertThat(r.temperatureLevel()).isEqualTo(level);
			assertThat(r.precipitation()).isEqualTo(Precipitation.NONE);
			assertThat(r.outfit().guide()).isNotBlank();
			assertThat(r.tableVersion()).isEqualTo("2026-10-draft");
		}
		OutfitRecommendation cold = service.recommend(1, Precipitation.NONE);
		assertThat(cold.outfit().outer()).containsExactly("롱패딩", "두꺼운 패딩");
		assertThat(cold.outfit().accessories()).containsExactly("목도리", "장갑", "귀마개");

		// 강수 보정: 소품만 더하고 겉옷은 바꾸지 않는다
		OutfitRecommendation rain = service.recommend(4, Precipitation.RAIN);
		assertThat(rain.outfit().outer()).isEqualTo(service.recommend(4, null).outfit().outer());
		assertThat(rain.outfit().accessories()).containsExactly("우산", "방수 신발");
		assertThat(service.recommend(1, Precipitation.SNOW).outfit().accessories()).containsExactly("목도리", "장갑",
				"귀마개", "우산", "미끄럼 방지 신발");

		assertThatThrownBy(() -> service.recommend(0, null)).isInstanceOf(DomainException.class);
		assertThatThrownBy(() -> service.recommend(8, null)).isInstanceOf(DomainException.class);
	}

	/** BR-OUT-03: 가장 추운 시각 기준 + 단계 차가 outfit.layering-gap(2) 이상이면 겹쳐 입기 안내. 어느 시각이든 비면 강수 보정. */
	@Test
	void departuresUseColdestTimeAndAdviseLayeringAtGap() {
		OutfitService service = service();
		DepartureOutfitRecommendation gap2 = service.recommendForDepartures(List.of(
				new DepartureCondition(MORNING, 3, Precipitation.NONE),
				new DepartureCondition(EVENING, 5, Precipitation.RAIN)));
		assertThat(gap2.basisTemperatureLevel()).isEqualTo(3);
		assertThat(gap2.basisDepartureAt()).isEqualTo(MORNING);
		assertThat(gap2.recommendation().temperatureLevel()).isEqualTo(3);
		assertThat(gap2.recommendation().precipitation()).isEqualTo(Precipitation.RAIN);
		assertThat(gap2.layeringAdvised()).isTrue();
		assertThat(gap2.layeringGuide()).isNotBlank();

		DepartureOutfitRecommendation gap1 = service.recommendForDepartures(List.of(
				new DepartureCondition(MORNING, 4, null), new DepartureCondition(EVENING, 3, null)));
		assertThat(gap1.basisDepartureAt()).isEqualTo(EVENING);
		assertThat(gap1.layeringAdvised()).isFalse();
		assertThat(gap1.layeringGuide()).isNull();
		assertThat(gap1.recommendation().precipitation()).isEqualTo(Precipitation.NONE);

		assertThatThrownBy(() -> service.recommendForDepartures(List.of())).isInstanceOf(DomainException.class);
	}

	/** 불변식: 매핑표에 빠진 단계가 있으면 설정 적용(기동)을 거부한다. */
	@Test
	void tableMissingALevelIsRejected() {
		runner.withPropertyValues("outfit.table.levels[6].level=6")
			.run(context -> assertThat(context).hasFailed());
	}

}
