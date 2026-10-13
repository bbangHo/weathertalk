package kr.weathertalk.neighborhood.internal.dataset;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * local 프로파일 전용: 동네가 하나도 없으면 가상의 SAMPLE 데이터셋(서울시청 주변 가상 사각형 5개)을 적재한다.
 * 실제 행정동 데이터가 아니다. 실제 출처가 정해지면 {@link DatasetLoadCommand}로 적재한다.
 */
@Component
@Profile("local")
class LocalSampleDatasetSeeder implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(LocalSampleDatasetSeeder.class);

	static final String VERSION = "SAMPLE-local-1";

	private final NeighborhoodDatasetLoader loader;
	private final JdbcClient jdbc;

	LocalSampleDatasetSeeder(NeighborhoodDatasetLoader loader, JdbcClient jdbc) {
		this.loader = loader;
		this.jdbc = jdbc;
	}

	@Override
	public void run(ApplicationArguments args) throws IOException {
		Integer count = jdbc.sql("SELECT count(*) FROM nbr_neighborhood").query(Integer.class).single();
		if (count > 0) {
			return;
		}
		try (Reader codes = reader("neighborhood/sample/SAMPLE-codes.csv");
				Reader boundaries = reader("neighborhood/sample/SAMPLE-boundaries.geojson")) {
			log.info("local SAMPLE 동네 데이터 적재: {}", loader.load(VERSION, codes, boundaries).summary());
		}
	}

	private static Reader reader(String path) throws IOException {
		return new InputStreamReader(new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8);
	}

}
