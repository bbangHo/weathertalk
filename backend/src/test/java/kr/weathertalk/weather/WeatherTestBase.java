package kr.weathertalk.weather;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.github.tomakehurst.wiremock.WireMockServer;

import kr.weathertalk.MutableClock;
import kr.weathertalk.neighborhood.NeighborhoodTestData;
import kr.weathertalk.neighborhood.internal.dataset.NeighborhoodDatasetLoader;

/**
 * 날씨 통합 테스트 공통: 매 테스트 전에 시각·동네 데이터·날씨 저장분·수집 점유·모의 서버를 초기화한다.
 * 동네 7(SAMPLE, 예보 격자 98·76, 자외선 구역 9900000000, 중기 구역 11B00000·11B10101)을 주로 쓴다.
 */
public abstract class WeatherTestBase {

	public static final String UV_AREA = "9900000000";
	public static final String MID_LAND_REGION = "11B00000";
	public static final String MID_TEMPERATURE_REGION = "11B10101";

	@Autowired
	protected WireMockServer server;

	@Autowired
	protected JdbcClient jdbc;

	@Autowired
	protected StringRedisTemplate redis;

	@Autowired
	protected MutableClock clock;

	@Autowired
	protected NeighborhoodDatasetLoader loader;

	@Autowired
	protected WeatherApi weatherApi;

	protected WeatherStubs stubs;

	@BeforeEach
	void resetWeather() {
		clock.reset();
		NeighborhoodTestData.loadBase(loader);
		WeatherStubs.resetAll(jdbc, redis, server);
		stubs = new WeatherStubs(server);
	}

}
