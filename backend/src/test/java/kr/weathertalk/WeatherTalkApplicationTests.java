package kr.weathertalk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class WeatherTalkApplicationTests {

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void contextLoadsWithPostgisEnabled() {
		String version = jdbc.queryForObject("SELECT postgis_version()", String.class);
		assertThat(version).isNotBlank();
	}

}
