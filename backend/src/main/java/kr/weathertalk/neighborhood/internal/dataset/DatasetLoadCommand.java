package kr.weathertalk.neighborhood.internal.dataset;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * 동네 기준 데이터 적재 명령. 인자가 있을 때만 동작하고, 적재가 끝나면 애플리케이션을 종료한다.
 *
 * <pre>
 * ./gradlew bootRun --args='--spring.main.web-application-type=none \
 *     --neighborhood.dataset.version=KMA-2607 \
 *     --neighborhood.dataset.codes=src/main/resources/neighborhood/kma/kma-administrative-dong-2607.csv'
 *     [--neighborhood.dataset.boundaries=/data/boundaries.geojson]   (선택: 경계가 있으면 포함 검사 우선)
 * </pre>
 *
 * 비교 보고서(신설·변경·폐지 코드 목록, 격자 검증 경고)를 로그로 남긴다. 운영 적재는 이 보고서를 확인한 뒤 진행한다(02 §3.2).
 */
@Component
class DatasetLoadCommand implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(DatasetLoadCommand.class);

	static final String VERSION = "neighborhood.dataset.version";
	static final String CODES = "neighborhood.dataset.codes";
	static final String BOUNDARIES = "neighborhood.dataset.boundaries";

	private final NeighborhoodDatasetLoader loader;
	private final ConfigurableApplicationContext context;

	DatasetLoadCommand(NeighborhoodDatasetLoader loader, ConfigurableApplicationContext context) {
		this.loader = loader;
		this.context = context;
	}

	@Override
	public void run(ApplicationArguments args) throws IOException {
		if (!args.containsOption(VERSION)) {
			return;
		}
		String version = single(args, VERSION);
		DatasetLoadReport report;
		try (Reader codes = Files.newBufferedReader(Path.of(single(args, CODES)), StandardCharsets.UTF_8)) {
			if (args.containsOption(BOUNDARIES)) {
				try (Reader boundaries = Files.newBufferedReader(Path.of(single(args, BOUNDARIES)),
						StandardCharsets.UTF_8)) {
					report = loader.load(version, codes, boundaries);
				}
			}
			else {
				report = loader.load(version, codes);
			}
		}
		log(report);
		System.exit(SpringApplication.exit(context, () -> 0));
	}

	static void log(DatasetLoadReport report) {
		log.info("동네 기준 데이터 적재 완료: {}", report.summary());
		log.info("신설: {}", report.added());
		log.info("변경: {}", report.changed());
		log.info("폐지: {}", report.retired());
		if (!report.gridWarnings().isEmpty()) {
			log.warn("예보 격자 검증 경고(파일 값으로 적재함) {}건: {}", report.gridWarnings().size(), report.gridWarnings());
		}
	}

	private static String single(ApplicationArguments args, String name) {
		List<String> values = args.getOptionValues(name);
		if (values == null || values.size() != 1 || values.getFirst().isBlank()) {
			throw new IllegalArgumentException("--" + name + " 값이 하나 필요합니다.");
		}
		return values.getFirst();
	}

}
