package kr.weathertalk.member.internal.config;

import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * member 운영 파라미터 (01-member.md §7).
 * <p>
 * 운영 파라미터 외부화 패턴(모든 모듈 공통):
 * <ul>
 * <li>모듈마다 {@code <module>.internal.config.<Module>Properties} record 하나, prefix는 스펙 키 그대로. 하위 키 묶음은 중첩 record.</li>
 * <li>초기값은 코드가 아니라 {@code application.yml}에만 둔다. 모든 값에 {@code @NotNull}을 붙여 yml에서 빠지면 기동이 실패하게 한다.</li>
 * <li>{@code @Validated} 제약(범위·대소 관계)이 깨지면 기동이 실패한다. 규칙 코드는 숫자를 하드코딩하지 않고 이 record를 주입받는다.</li>
 * <li>등록은 같은 패키지의 {@code <Module>PropertiesConfig}가 한다(모듈 테스트에서 그 모듈 설정만 올라오도록).</li>
 * </ul>
 * 런타임 변경(재기동 없는 갱신)은 아직 지원하지 않는다. 지금은 환경 변수·프로파일 yml로 바꾸고 재기동한다.
 * 재기동 없는 갱신이 필요해지면 M8 운영 단계에서 Spring Cloud Config(+ 갱신 이벤트) 또는 DB 설정 테이블을 검토한다.
 */
@Validated
@ConfigurationProperties("member")
public record MemberProperties(
		@NotNull @DurationMin(minutes = 1) Duration accessTokenTtl,
		@NotNull @DurationMin(hours = 1) Duration refreshTokenTtl,
		@NotNull @DurationMin(seconds = 0) Duration refreshReuseGrace,
		@NotNull @DurationMin(minutes = 1) Duration signupTicketTtl,
		@NotNull @Valid Nickname nickname,
		@NotNull @Valid ExternalUnlink externalUnlink,
		@NotNull @Valid Token token) {

	@AssertTrue(message = "갱신 토큰 수명은 접근 토큰 수명보다 길어야 합니다")
	public boolean isRefreshLongerThanAccess() {
		return accessTokenTtl == null || refreshTokenTtl == null || refreshTokenTtl.compareTo(accessTokenTtl) > 0;
	}

	public record Nickname(
			@NotNull @Min(1) Integer maxAttempts,
			@NotNull @DurationMin(seconds = 0) Duration changeInterval,
			@NotNull @Min(1) Integer minLength,
			@NotNull @Min(1) Integer maxLength) {

		@AssertTrue(message = "닉네임 최소 길이는 최대 길이 이하여야 합니다")
		public boolean isLengthRangeValid() {
			return minLength == null || maxLength == null || minLength <= maxLength;
		}

	}

	/**
	 * @param pollInterval 재시도 대기 작업을 확인하는 주기(스펙 §7에 없는 키). 첫 시도는 커밋 직후 바로 한다
	 */
	public record ExternalUnlink(
			@NotNull @Min(1) Integer maxAttempts,
			@NotNull @DurationMin(seconds = 1) Duration backoff,
			@NotNull @DurationMin(days = 1) Duration retention,
			@NotNull @DurationMin(seconds = 1) Duration pollInterval) {
	}

	/**
	 * @param secret 접근·갱신 토큰 서명 비밀값. 종류별 서명 키는 이 값에서 파생한다. 운영(prod)은 환경 변수 MEMBER_TOKEN_SECRET 필수 —
	 *        비어 있으면 기동이 실패한다. local·test는 application.yml의 개발용 기본값을 쓴다.
	 */
	public record Token(@NotBlank @Size(min = 32, message = "토큰 서명 비밀값은 32자 이상이어야 합니다") String secret) {

		@Override
		public String toString() {
			return "Token[secret=***]";
		}

	}

}
