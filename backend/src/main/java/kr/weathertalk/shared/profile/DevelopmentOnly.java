package kr.weathertalk.shared.profile;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Profile;

/**
 * 개발용 기능(개발 전용 로그인, 테스트 발송 등)은 local·staging·test 프로파일에서만 빈으로 등록한다.
 * 운영(prod)에는 등록되지 않는다 (contracts.md §4 개발용 기능, legacy 01-#21).
 */
@Documented
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Profile({ DevelopmentOnly.LOCAL, DevelopmentOnly.STAGING, DevelopmentOnly.TEST })
public @interface DevelopmentOnly {

	String LOCAL = "local";

	String STAGING = "staging";

	String TEST = "test";

}
