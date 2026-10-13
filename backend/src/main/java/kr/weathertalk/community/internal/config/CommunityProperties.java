package kr.weathertalk.community.internal.config;

import java.time.Duration;
import java.util.List;

import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * community 운영 파라미터 (README 운영 파라미터, 05-community.md §7). 외부화 패턴은 MemberProperties Javadoc 참조.
 *
 * @param idempotencyWindow 생성 요청 식별자 보관 기간. {@code IdempotentExecutor}에 TTL로 넘긴다.
 */
@Validated
@ConfigurationProperties("community")
public record CommunityProperties(
		@NotNull @Min(0) Integer maxImages,
		@NotNull @Valid Post post,
		@NotNull @Valid Comment comment,
		@NotNull @Valid Image image,
		@NotNull @DurationMin(minutes = 1) Duration feelingAttachWindow,
		@NotNull @Valid Feed feed,
		@NotNull @Valid Popular popular,
		@NotNull @DurationMin(minutes = 1) Duration idempotencyWindow,
		@NotNull @Valid AdminDeletion adminDeletion) {

	public record Post(@NotNull @Min(1) Integer minLength, @NotNull @Min(1) Integer maxLength) {

		@AssertTrue(message = "게시글 본문 최소 길이는 최대 길이 이하여야 합니다")
		public boolean isLengthRangeValid() {
			return minLength == null || maxLength == null || minLength <= maxLength;
		}

	}

	public record Comment(@NotNull @Min(1) Integer maxLength, @NotNull @Min(1) Integer pageSize) {
	}

	/**
	 * @param allowedTypes 허용 MIME 형식. 서버가 디코딩할 수 있는 형식만 둘 수 있다(기동 시 확인, ImageSanitizer)
	 * @param maxDimension 저장 시 긴 변 최대 픽셀
	 * @param uploadUrlTtl 사전 서명 업로드 주소 유효 시간
	 * @param viewUrlTtl 표시용 사전 서명 주소 유효 시간(저장소를 공개하지 않으므로 표시 주소도 서명한다, 05 §9 Q9)
	 * @param jpegQuality 다시 인코딩할 때 JPEG 품질(0~1)
	 */
	public record Image(
			@NotNull DataSize maxFileSize,
			@NotEmpty List<@NotBlank String> allowedTypes,
			@NotNull @Min(1) Integer maxDimension,
			@NotNull @DurationMin(minutes = 1) Duration orphanTtl,
			@NotNull @DurationMin(minutes = 1) Duration cleanupInterval,
			@NotNull @DurationMin(minutes = 1) @DurationMax(minutes = 60) Duration uploadUrlTtl,
			@NotNull @DurationMin(minutes = 5) @DurationMax(days = 7) Duration viewUrlTtl,
			@NotNull @DecimalMin("0.1") @DecimalMax("1.0") Double jpegQuality) {

		@AssertTrue(message = "이미지 최대 크기는 0보다 커야 합니다")
		public boolean isMaxFileSizePositive() {
			return maxFileSize == null || maxFileSize.toBytes() > 0;
		}

	}

	public record Feed(@NotNull @Min(1) Integer pageSize, @NotNull @Min(1) Integer maxPageSize) {

		@AssertTrue(message = "피드 기본 개수는 최대 개수 이하여야 합니다")
		public boolean isPageSizeWithinMax() {
			return pageSize == null || maxPageSize == null || pageSize <= maxPageSize;
		}

	}

	public record Popular(
			@NotNull @DurationMin(hours = 1) Duration window,
			@NotNull @Min(0) Integer minLikes,
			@NotNull @Min(1) Integer size) {
	}

	public record AdminDeletion(@NotNull @Min(1) Integer reasonMaxLength) {
	}

}
