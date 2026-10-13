package kr.weathertalk.community.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import kr.weathertalk.shared.error.DomainException;

/** 순수 규칙: 본문 길이(BR-COM-03), 커서 형식(BR-COM-08). */
class CommunityRulesTests {

	@Test
	void bodyLengthCountsVisibleCharactersForMinimumAndAllCharactersForMaximum() {
		assertThat(TextRules.body("  a  ", 1, 3, "게시글")).isEqualTo("a");
		assertThat(TextRules.body("a b", 1, 3, "게시글")).isEqualTo("a b");
		assertThat(TextRules.body("😀😀😀", 1, 3, "게시글")).isEqualTo("😀😀😀");
		assertThatThrownBy(() -> TextRules.body("a  b", 1, 3, "게시글")).isInstanceOf(DomainException.class)
			.satisfies(e -> assertThat(((DomainException) e).details()).containsEntry("reason", "TOO_LONG"));
		assertThatThrownBy(() -> TextRules.body("　 \n", 1, 3, "게시글"))
			.satisfies(e -> assertThat(((DomainException) e).details()).containsEntry("reason", "BLANK"));
		assertThatThrownBy(() -> TextRules.body("a b", 3, 5, "게시글"))
			.satisfies(e -> assertThat(((DomainException) e).details()).containsEntry("reason", "TOO_SHORT"));
	}

	@Test
	void cursorRoundTripsAndRejectsGarbage() {
		PageCursor cursor = new PageCursor(Instant.parse("2026-10-13T00:15:30.123456Z"), UUID.randomUUID());
		assertThat(PageCursor.decode(cursor.encode())).isEqualTo(cursor);
		assertThat(PageCursor.decode(null)).isNull();
		assertThat(PageCursor.decode(" ")).isNull();
		assertThatThrownBy(() -> PageCursor.decode("!!!")).isInstanceOf(DomainException.class);
		assertThatThrownBy(() -> PageCursor.decode("bm90LWEtY3Vyc29y")).isInstanceOf(DomainException.class);
	}

}
