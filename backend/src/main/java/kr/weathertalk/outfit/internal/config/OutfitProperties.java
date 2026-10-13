package kr.weathertalk.outfit.internal.config;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * outfit 운영 파라미터 (04-feeling.md 옷 추천 장 §7). 외부화 패턴은 MemberProperties Javadoc 참조.
 *
 * @param layeringGap 일교차 겹쳐 입기 안내 기준 온도 태그 단계 차
 * @param table 옷차림 매핑표(강수 보정 포함). 교체는 설정 변경으로 한다(BR-OUT-04)
 */
@Validated
@ConfigurationProperties("outfit")
public record OutfitProperties(@NotNull @Min(1) Integer layeringGap, @NotNull @Valid Table table) {

	/**
	 * 옷차림 매핑표. 불변식: 온도 태그 1~7단계 모두에 옷차림이 정확히 하나씩 있다(빠지면 기동 실패).
	 *
	 * @param rainAccessories 강수 '비'일 때 소품에 더하는 것
	 * @param snowAccessories 강수 '눈'일 때 소품에 더하는 것
	 */
	public record Table(
			@NotBlank String version,
			@NotBlank String layeringGuide,
			@NotNull List<@NotBlank String> rainAccessories,
			@NotNull List<@NotBlank String> snowAccessories,
			@NotEmpty List<@NotNull @Valid Level> levels) {

		@AssertTrue(message = "outfit.table.levels는 온도 태그 1~7단계를 정확히 하나씩 가져야 합니다")
		public boolean isComplete() {
			if (levels == null) {
				return true;
			}
			Set<Integer> present = levels.stream()
				.map(Level::level)
				.filter(l -> l != null)
				.collect(Collectors.toSet());
			return levels.size() == 7 && present.equals(IntStream.rangeClosed(1, 7).boxed().collect(Collectors.toSet()));
		}

	}

	public record Level(
			@NotNull @Min(1) @Max(7) Integer level,
			@NotNull List<@NotBlank String> outer,
			@NotNull List<@NotBlank String> top,
			@NotNull List<@NotBlank String> bottom,
			@NotNull List<@NotBlank String> accessories,
			@NotBlank String guide) {
	}

}
