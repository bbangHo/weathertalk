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
 * local 프로파일 전용: 기상청 전국 행정동 자료(ADR 0016)를 시드로 적재한다. 이 버전이 이미 적재돼 있으면 건너뛴다.
 * 운영은 {@link DatasetLoadCommand}로 비교 보고서를 확인하며 적재한다.
 */
@Component
@Profile("local")
class LocalDatasetSeeder implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(LocalDatasetSeeder.class);

	static final String VERSION = "KMA-2607";
	static final String RESOURCE = "neighborhood/kma/kma-administrative-dong-2607.csv";

	private final NeighborhoodDatasetLoader loader;
	private final JdbcClient jdbc;

	LocalDatasetSeeder(NeighborhoodDatasetLoader loader, JdbcClient jdbc) {
		this.loader = loader;
		this.jdbc = jdbc;
	}

	@Override
	public void run(ApplicationArguments args) throws IOException {
		Integer loaded = jdbc.sql("SELECT count(*) FROM nbr_dataset_version WHERE version = :v")
			.param("v", VERSION)
			.query(Integer.class)
			.single();
		if (loaded > 0) {
			return;
		}
		try (Reader codes = new InputStreamReader(new ClassPathResource(RESOURCE).getInputStream(),
				StandardCharsets.UTF_8)) {
			log.info("local 동네 기준 데이터(기상청 전국) 시드 적재");
			DatasetLoadCommand.log(loader.load(VERSION, codes));
		}
	}

}
