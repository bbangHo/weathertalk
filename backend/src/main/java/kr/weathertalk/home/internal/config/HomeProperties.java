package kr.weathertalk.home.internal.config;

import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * home 운영 파라미터 (07-home-and-screens.md 설정 파라미터). 외부화 패턴은 MemberProperties Javadoc 참조.
 * {@code app.*} 키는 앱이 쓰는 값이라 서버 설정에 두지 않는다(전달 방식은 07 문서 미결 §9-6).
 *
 * @param sectionTimeout 섹션별 하위 모듈 조회 제한 시간. 넘으면 그 섹션만 실패로 처리한다.
 */
@Validated
@ConfigurationProperties("home")
public record HomeProperties(
		@NotNull @DurationMin(millis = 100) Duration sectionTimeout,
		@NotNull @Min(0) Integer popularPostsCount) {
}
