package kr.weathertalk.outfit.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * outfit 운영 파라미터 (04-feeling.md 옷 추천 장 §7). 외부화 패턴은 MemberProperties Javadoc 참조.
 * <p>
 * {@code outfit.table}(옷차림 매핑표)은 표 구조(온도 체감 태그 × 강수 보정)와 함께 M3에서 추가한다.
 *
 * @param layeringGap 일교차 겹쳐 입기 안내 기준 온도 태그 단계 차
 */
@Validated
@ConfigurationProperties("outfit")
public record OutfitProperties(@NotNull @Min(1) Integer layeringGap) {
}
