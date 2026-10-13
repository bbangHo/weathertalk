package kr.weathertalk;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

@Modulithic(sharedModules = "shared")
@SpringBootApplication
public class WeatherTalkApplication {

	public static void main(String[] args) {
		// 모든 날짜·시각 판정은 Asia/Seoul 기준 (contracts.md §4)
		TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
		SpringApplication.run(WeatherTalkApplication.class, args);
	}

}
