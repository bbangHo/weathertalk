package kr.weathertalk.feeling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

/**
 * 체감 태그 값 체계 (04 §3.1)와 피드백 변환 (BR-FEL-06).
 */
class FeelingTagTests {

	/** legacy 03-#16: 온도 7단계는 의미 순서로 정렬된 단일 정의 — 단계 번호 = 선언 순서 = 1..7. */
	@Test
	void legacy03_16_temperatureTagsAreOrderedBySingleDefinition() {
		assertThat(Arrays.stream(TemperatureTag.values()).mapToInt(TemperatureTag::level)).containsExactly(1, 2, 3, 4,
				5, 6, 7);
		assertThat(Arrays.stream(TemperatureTag.values()).map(TemperatureTag::label)).containsExactly("매우 추움", "추움",
				"조금 추움", "딱 좋음", "조금 더움", "더움", "매우 더움");
		assertThat(TemperatureTag.NEUTRAL.level()).isEqualTo(4);
		for (TemperatureTag tag : TemperatureTag.values()) {
			assertThat(tag.ordinal() + 1).isEqualTo(tag.level());
		}
	}

	/** legacy 03-#4: 모든 태그 값이 같은 정의에서 검증된다(현행 바람 4단계 검증 실패 재발 방지) — 범위 안은 전부 통과, 밖은 거부. */
	@Test
	void legacy03_4_everyTagValueIsAcceptedAndOutOfRangeIsRejected() {
		for (int level = 1; level <= 7; level++) {
			assertThat(TemperatureTag.of(level).level()).isEqualTo(level);
		}
		for (int level = 1; level <= 5; level++) {
			assertThat(HumidityTag.of(level).level()).isEqualTo(level);
		}
		for (int level = 1; level <= 3; level++) {
			assertThat(WindTag.of(level).level()).isEqualTo(level);
		}
		assertThatThrownBy(() -> TemperatureTag.of(0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> TemperatureTag.of(8)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> HumidityTag.of(6)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> WindTag.of(4)).isInstanceOf(IllegalArgumentException.class);
		// 습도 중립은 가운데, 바람은 한쪽 방향 강도 척도
		assertThat(HumidityTag.NEUTRAL.level()).isEqualTo(3);
		assertThat(WindTag.NEUTRAL.level()).isEqualTo(1);
		assertThat(Arrays.stream(WindTag.values()).map(WindTag::label)).containsExactly("잔잔해요", "조금 불어요", "세게 불어요");
	}

	/** BR-FEL-06: 맞아요 = 그대로, 더 추워요 = 한 단계 아래, 더 더워요 = 한 단계 위, 양 끝은 유지. */
	@Test
	void feedbackConvertsOneStepFromShownTagAndKeepsEnds() {
		for (TemperatureTag shown : TemperatureTag.values()) {
			assertThat(FeedbackAnswer.RIGHT.apply(shown)).isEqualTo(shown);
			assertThat(FeedbackAnswer.COLDER.apply(shown).level()).isEqualTo(Math.max(1, shown.level() - 1));
			assertThat(FeedbackAnswer.HOTTER.apply(shown).level()).isEqualTo(Math.min(7, shown.level() + 1));
		}
		assertThat(FeedbackAnswer.COLDER.apply(TemperatureTag.VERY_COLD)).isEqualTo(TemperatureTag.VERY_COLD);
		assertThat(FeedbackAnswer.HOTTER.apply(TemperatureTag.VERY_HOT)).isEqualTo(TemperatureTag.VERY_HOT);
		assertThat(FeedbackAnswer.COLDER.apply(TemperatureTag.JUST_RIGHT)).isEqualTo(TemperatureTag.SLIGHTLY_COLD);
	}

}
