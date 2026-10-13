package kr.weathertalk;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.EnableScenarios;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * 통합 테스트 공통 설정: 전체 애플리케이션 + 공유 컨테이너 + test 프로파일(개발 전용 로그인 등록) + 옮길 수 있는 Clock.
 * 모든 통합 테스트가 같은 설정을 써서 컨텍스트 하나를 재사용한다. 데이터는 테스트끼리 공유되므로 무작위 ID를 쓰고
 * 전역 상태(시각, 약관, 동네 데이터)를 바꾼 테스트는 끝나면 되돌린다.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({ TestcontainersConfiguration.class, IntegrationTestConfiguration.class })
@RecordApplicationEvents
@EnableScenarios
public @interface IntegrationTest {

}
