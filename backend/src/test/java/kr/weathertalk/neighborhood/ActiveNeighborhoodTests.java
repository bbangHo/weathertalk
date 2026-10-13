package kr.weathertalk.neighborhood;

import static kr.weathertalk.neighborhood.NeighborhoodTestData.INSIDE_N1;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N1;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N2;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N3;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N7;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import kr.weathertalk.IntegrationTest;
import kr.weathertalk.MutableClock;
import kr.weathertalk.neighborhood.internal.dataset.NeighborhoodDatasetLoader;
import kr.weathertalk.shared.Ids.MemberId;

/**
 * 활성 동네·활성 예보 격자·예보 격자의 활성 동네 (FR-NBR-09, BR-NBR-09).
 * 다른 테스트가 만든 회원과 겹치지 않도록 먼 미래 시각에서 확정한다(최근 활동 창 밖의 과거 확정은 활성 사유가 아니다).
 */
@IntegrationTest
class ActiveNeighborhoodTests {

	@Autowired
	NeighborhoodApi neighborhoodApi;

	@Autowired
	NeighborhoodDatasetLoader loader;

	@Autowired
	MutableClock clock;

	@Autowired
	org.springframework.jdbc.core.simple.JdbcClient jdbc;

	@BeforeEach
	void setUp() {
		clock.reset();
		NeighborhoodTestData.loadBase(loader);
	}

	@Test
	void activeNeighborhoodsComeFromRecentlyActiveMembersCurrentAndInterestNeighborhoods() {
		clock.set(MutableClock.BASE.plus(Duration.ofDays(1000)));
		MemberId member = MemberId.random();
		neighborhoodApi.confirmCurrentNeighborhood(member, INSIDE_N1).orElseThrow();
		neighborhoodApi.mergeInterestNeighborhoods(member, List.of(N3, N7));
		MemberId interestOnly = MemberId.random(); // 현재 동네 확정 이력이 없는 회원의 관심 동네는 활성 사유가 아니다
		neighborhoodApi.mergeInterestNeighborhoods(interestOnly, List.of(N2));

		assertThat(neighborhoodApi.findActiveNeighborhoods()).containsExactly(N1, N3, N7);

		Neighborhood n1 = neighborhoodApi.findNeighborhood(N1).orElseThrow();
		Neighborhood n2 = neighborhoodApi.findNeighborhood(N2).orElseThrow();
		Neighborhood n7 = neighborhoodApi.findNeighborhood(N7).orElseThrow();
		assertThat(neighborhoodApi.findActiveForecastGrids()).contains(n1.forecastGrid(), n7.forecastGrid())
			.doesNotHaveDuplicates();
		// 같은 격자를 공유하는 N2는 활성 동네가 아니므로 격자의 활성 동네에서 빠진다.
		assertThat(n2.forecastGrid()).isEqualTo(n1.forecastGrid());
		assertThat(neighborhoodApi.findActiveNeighborhoods(n1.forecastGrid())).contains(N1).doesNotContain(N2);
		assertThat(neighborhoodApi.findActiveNeighborhoods(new ForecastGrid(98, 76))).containsExactly(N7);

		// 최근 활동 창(30일)이 지나면 활성 사유에서 빠진다(FR-NBR-08).
		clock.advance(Duration.ofDays(30).plusSeconds(1));
		assertThat(neighborhoodApi.findActiveNeighborhoods()).isEmpty();
		assertThat(neighborhoodApi.findActiveForecastGrids()).isEmpty();
	}


	/**
	 * FR-NBR-10: 알림 구독 수가 1 이상인 동네는 회원 활동이 없어도 활성 동네다. 0 아래로 내려가는 감소는 0에서 멈추고(경고 로그),
	 * 없는 동네는 거부한다. 대조는 주어진 값으로 통째로 바꾼다(목록에 없는 동네는 0).
	 */
	@Test
	void notificationSubscriptionCountsAddActiveNeighborhoods() {
		clock.set(MutableClock.BASE.plus(Duration.ofDays(1000)));
		jdbc.sql("DELETE FROM nbr_notification_subscription").update();
		try {
			neighborhoodApi.applyNotificationSubscriptionChange(N2, 1);
			neighborhoodApi.applyNotificationSubscriptionChange(N2, 1);
			assertThat(neighborhoodApi.findActiveNeighborhoods()).containsExactly(N2);
			assertThat(neighborhoodApi.findActiveForecastGrids()).hasSize(1);

			neighborhoodApi.applyNotificationSubscriptionChange(N2, -3);
			assertThat(neighborhoodApi.findActiveNeighborhoods()).isEmpty();
			neighborhoodApi.applyNotificationSubscriptionChange(N2, 1);
			assertThat(neighborhoodApi.findActiveNeighborhoods()).containsExactly(N2);

			assertThatThrownBy(() -> neighborhoodApi
				.applyNotificationSubscriptionChange(new kr.weathertalk.shared.Ids.NeighborhoodId("0000000000"), 1))
				.isInstanceOf(kr.weathertalk.shared.error.DomainException.class);

			assertThat(neighborhoodApi.reconcileNotificationSubscriptions(java.util.Map.of(N3, 2))).isEqualTo(2);
			assertThat(neighborhoodApi.findActiveNeighborhoods()).containsExactly(N3);
		}
		finally {
			jdbc.sql("DELETE FROM nbr_notification_subscription").update();
		}
	}

}
