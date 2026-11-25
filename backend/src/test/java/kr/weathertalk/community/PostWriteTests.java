package kr.weathertalk.community;

import static kr.weathertalk.neighborhood.NeighborhoodTestData.UNKNOWN;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.INSIDE_N1;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N1;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.N2;
import static kr.weathertalk.neighborhood.NeighborhoodTestData.NEAR_N2;
import static kr.weathertalk.weather.WeatherStubs.kst;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;

import kr.weathertalk.AuthTestClient;
import kr.weathertalk.IntegrationTest;
import kr.weathertalk.feeling.FeelingRecordSource;
import kr.weathertalk.member.TemperatureSensitivity;
import kr.weathertalk.shared.idempotency.IdempotencyKey;

/**
 * 게시글 작성·수정·삭제 (FR-COM-02·03·04, BR-COM-01·03·04·06·07·09·12).
 */
@IntegrationTest
class PostWriteTests extends CommunityTestBase {

	static final Instant NOW = kst("2026-10-13T09:15");

	@Autowired
	ApplicationEvents events;

	/** 비회원은 가입 필요, 약관 재동의 전에는 작성 불가, 현재 동네를 확정하지 못하면 작성 불가(contracts §4). */
	@Test
	void writeGuards() throws Exception {
		clock.set(NOW);
		mvc.perform(post("/api/v1/community/posts").header(IdempotencyKey.HEADER, newKey())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"body\":\"안녕\"," + locationJson(INSIDE_N1) + "}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("SIGNUP_REQUIRED"));

		AuthTestClient.Session session = member();
		createPost(session, newKey(), "바다 위", List.of(), null, UNKNOWN).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("CURRENT_NEIGHBORHOOD_UNVERIFIED"));

		UUID mine = newPost(session, "재동의 전에 쓴 글");
		jdbc.sql("INSERT INTO member_terms (kind, version, title, required, major_revision, effective_at, content_url) "
				+ "VALUES ('SERVICE', 97, 'v97', true, true, '2026-01-02 00:00:00+09', 'about:blank')").update();
		try {
			createPost(session, "재동의 전").andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("TERMS_REAGREEMENT_REQUIRED"));
			// 본인 글 삭제는 재동의 전에도 허용
			mvc.perform(auth(delete("/api/v1/community/posts/" + mine), session)).andExpect(status().isNoContent());
		}
		finally {
			jdbc.sql("DELETE FROM member_terms WHERE version = 97").update();
		}
		assertThat(count("community_post")).isZero();
	}

	/** legacy 07-#10, 03-#21 (BR-COM-03): 본문 필수, 공백 제외 1자 이상, 최대 1,000자. 서버가 최종 검증한다. */
	@Test
	void legacy07_10_bodyIsRequiredAndBetween1And1000Characters() throws Exception {
		clock.set(NOW);
		AuthTestClient.Session session = member();
		createPost(session, null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.reason").value("BLANK"));
		createPost(session, "").andExpect(status().isBadRequest()).andExpect(jsonPath("$.reason").value("BLANK"));
		createPost(session, "  \n\t  ").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		createPost(session, "가".repeat(1001)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("TOO_LONG"));
		// 이모지 1,000개(코드 포인트 기준)는 1,000자다
		createPost(session, "🌧".repeat(1000)).andExpect(status().isOk());
		createPost(session, "가".repeat(1000)).andExpect(status().isOk());
		UUID one = newPost(session, "  비  ");
		assertThat(jdbc.sql("SELECT body FROM community_post WHERE id = :id").param("id", one).query(String.class).single())
			.isEqualTo("비");
		assertThat(count("community_post")).isEqualTo(3);
	}

	/** BR-COM-01: 게시글의 동네는 작성 직전 확정한 현재 동네이고, 작성자의 현재 동네가 바뀌어도 그대로다. */
	@Test
	void postNeighborhoodIsFixedAtWriteTime() throws Exception {
		clock.set(NOW);
		AuthTestClient.Session session = member();
		UUID postId = newPost(session, "여기는 동네 1");
		neighborhoodApi.confirmCurrentNeighborhood(session.memberId(), NEAR_N2).orElseThrow();
		detail(null, postId).andExpect(status().isOk())
			.andExpect(jsonPath("$.post.neighborhood.id").value(N1.value()))
			.andExpect(jsonPath("$.post.neighborhood.name").value(
					neighborhoodApi.findNeighborhood(N1).orElseThrow().eupmyeondongName()))
			// 비회원 조회에는 좋아요·본인 여부가 없다
			.andExpect(jsonPath("$.post.likedByViewer").doesNotExist())
			.andExpect(jsonPath("$.post.author.nickname").isNotEmpty())
			.andExpect(jsonPath("$.post.author.temperatureSensitivity").value("NORMAL"));
		feed(null, N2, null, null).andExpect(jsonPath("$.items.length()").value(0));
	}

	/** BR-COM-12: 같은 요청 식별자로 다시 보내면 새로 만들지 않고 처음 결과를 돌려준다. 게시글작성됨 발행. */
	@Test
	void sameIdempotencyKeyReturnsFirstResultAndPublishesPostCreatedOnce() throws Exception {
		clock.set(NOW);
		AuthTestClient.Session session = member();
		String key = newKey();
		UUID first = postId(createPost(session, key, "한 번만", List.of(), null, INSIDE_N1));
		UUID second = postId(createPost(session, key, "한 번만", List.of(), null, INSIDE_N1));
		assertThat(second).isEqualTo(first);
		assertThat(count("community_post")).isEqualTo(1);
		assertThat(events.stream(PostCreated.class).filter(e -> e.postId().value().equals(first))).singleElement()
			.satisfies(e -> {
				assertThat(e.authorId()).isEqualTo(session.memberId());
				assertThat(e.neighborhoodId()).isEqualTo(N1);
			});
		// 구독자가 없는 이벤트는 발행 저장소에 미완료로 쌓이지 않는다(후순위 원칙 — M7에서 구독자를 붙인다)
		assertThat(jdbc.sql("SELECT count(*) FROM event_publication WHERE event_type = :t AND completion_date IS NULL")
			.param("t", PostCreated.class.getName())
			.query(Integer.class)
			.single()).isZero();
	}

	/** FR-COM-02 규칙 3, BR-COM-04: 본인이 올린 미첨부 이미지만, 최대 community.max-images장, 한 게시글에만. */
	@Test
	void imagesMustBeOwnUnattachedAndAtMostMaxImages() throws Exception {
		clock.set(NOW);
		AuthTestClient.Session session = member();
		AuthTestClient.Session other = member();
		List<UUID> five = List.of(insertUnattachedImage(session.memberId().value()),
				insertUnattachedImage(session.memberId().value()), insertUnattachedImage(session.memberId().value()),
				insertUnattachedImage(session.memberId().value()), insertUnattachedImage(session.memberId().value()));
		createPost(session, newKey(), "다섯 장", five, null, INSIDE_N1).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("POST_IMAGE_NOT_ATTACHABLE"));
		UUID othersImage = insertUnattachedImage(other.memberId().value());
		createPost(session, newKey(), "남의 사진", List.of(five.get(0), othersImage), null, INSIDE_N1)
			.andExpect(status().isUnprocessableContent());
		// 부분 성공 없음: 실패한 작성은 게시글도, 이미지 첨부도 남기지 않는다
		assertThat(count("community_post")).isZero();
		assertThat(imageStatus(five.get(0))).isEqualTo("UNATTACHED");

		UUID postId = postId(createPost(session, newKey(), "네 장", five.subList(0, 4), null, INSIDE_N1));
		five.subList(0, 4).forEach(id -> assertThat(imageStatus(id)).isEqualTo("ATTACHED"));
		detail(session, postId).andExpect(jsonPath("$.post.images.length()").value(4))
			.andExpect(jsonPath("$.post.images[0].imageId").value(five.get(0).toString()))
			.andExpect(jsonPath("$.post.images[3].imageId").value(five.get(3).toString()));
		createPost(session, newKey(), "이미 붙은 사진", List.of(five.get(0)), null, INSIDE_N1)
			.andExpect(status().isUnprocessableContent());
	}

	/** BR-COM-06: 본인 기록, 같은 동네, 작성 시각 기준 community.feeling-attach-window(3시간) 이내. */
	@Test
	void feelingRecordAttachmentConditions() throws Exception {
		clock.set(NOW);
		AuthTestClient.Session session = member();
		AuthTestClient.Session other = member();
		UUID me = session.memberId().value();
		UUID ok = insertRecord(N1, TemperatureSensitivity.NORMAL, NOW.minus(Duration.ofHours(3)), 3, null, null,
				FeelingRecordSource.DIRECT, me, null);
		UUID tooOld = insertRecord(N1, TemperatureSensitivity.NORMAL, NOW.minus(Duration.ofHours(3)).minusSeconds(1), 3,
				null, null, FeelingRecordSource.DIRECT, me, null);
		UUID otherNeighborhood = insertRecord(N2, TemperatureSensitivity.NORMAL, NOW, 3, null, null,
				FeelingRecordSource.DIRECT, me, null);
		UUID othersRecord = insertRecord(N1, TemperatureSensitivity.NORMAL, NOW, 3, null, null,
				FeelingRecordSource.DIRECT, other.memberId().value(), null);

		createPost(session, newKey(), "남의 기록", List.of(), othersRecord, INSIDE_N1)
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("FEELING_RECORD_NOT_ATTACHABLE"))
			.andExpect(jsonPath("$.reason").value("NOT_OWNED"));
		createPost(session, newKey(), "없는 기록", List.of(), UUID.randomUUID(), INSIDE_N1)
			.andExpect(jsonPath("$.reason").value("NOT_OWNED"));
		createPost(session, newKey(), "다른 동네 기록", List.of(), otherNeighborhood, INSIDE_N1)
			.andExpect(jsonPath("$.reason").value("NEIGHBORHOOD_MISMATCH"));
		createPost(session, newKey(), "오래된 기록", List.of(), tooOld, INSIDE_N1)
			.andExpect(jsonPath("$.reason").value("TOO_OLD"));
		assertThat(count("community_post")).isZero();

		UUID postId = postId(createPost(session, newKey(), "딱 3시간 전 기록", List.of(), ok, INSIDE_N1));
		detail(null, postId).andExpect(jsonPath("$.post.attachedFeelingRecord.recordId").value(ok.toString()))
			.andExpect(jsonPath("$.post.attachedFeelingRecord.temperature").value("SLIGHTLY_COLD"))
			.andExpect(jsonPath("$.post.attachedFeelingRecord.temperatureSensitivity").value("NORMAL"));
		// 첨부된 기록을 조회할 수 없으면 첨부 없이 보여준다(FR-COM-05 규칙 6)
		jdbc.sql("DELETE FROM feeling_record WHERE id = :id").param("id", ok).update();
		detail(null, postId).andExpect(status().isOk()).andExpect(jsonPath("$.post.attachedFeelingRecord").doesNotExist());
	}

	/** FR-COM-03, BR-COM-07: 본문·이미지·체감 기록 첨부 해제만. 작성자만. 빠진 이미지는 정리 대상. 동네는 그대로. */
	@Test
	void updateChangesBodyImagesAndDetachesFeelingOnly() throws Exception {
		clock.set(NOW);
		AuthTestClient.Session session = member();
		AuthTestClient.Session other = member();
		UUID me = session.memberId().value();
		UUID record = insertRecord(N1, TemperatureSensitivity.NORMAL, NOW, 4, null, null, FeelingRecordSource.DIRECT, me,
				null);
		UUID a = insertUnattachedImage(me);
		UUID b = insertUnattachedImage(me);
		UUID c = insertUnattachedImage(me);
		UUID postId = postId(createPost(session, newKey(), "처음", List.of(a, b), record, INSIDE_N1));

		String update = "{\"body\":\"고침\",\"imageIds\":" + ids(List.of(c, a)) + ",\"detachFeelingRecord\":true}";
		mvc.perform(auth(put("/api/v1/community/posts/" + postId), other).contentType(MediaType.APPLICATION_JSON)
			.content(update)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NOT_CONTENT_AUTHOR"));

		clock.advance(Duration.ofMinutes(5));
		mvc.perform(auth(put("/api/v1/community/posts/" + postId), session).contentType(MediaType.APPLICATION_JSON)
			.content(update))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.body").value("고침"))
			.andExpect(jsonPath("$.edited").value(true))
			.andExpect(jsonPath("$.images[0].imageId").value(c.toString()))
			.andExpect(jsonPath("$.images[1].imageId").value(a.toString()))
			.andExpect(jsonPath("$.attachedFeelingRecord").doesNotExist())
			.andExpect(jsonPath("$.neighborhood.id").value(N1.value()));
		assertThat(imageStatus(b)).isEqualTo("DISPOSABLE");
		assertThat(imageStatus(c)).isEqualTo("ATTACHED");
		// 수정에는 동네·체감 기록 첨부 입력이 없다 — 본문 검증은 작성과 같다
		mvc.perform(auth(put("/api/v1/community/posts/" + postId), session).contentType(MediaType.APPLICATION_JSON)
			.content("{\"body\":\" \",\"imageIds\":[]}")).andExpect(status().isBadRequest());
		mvc.perform(auth(put("/api/v1/community/posts/" + UUID.randomUUID()), session)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"body\":\"없는 글\"}")).andExpect(status().isNotFound());
	}

	/** FR-COM-04, BR-COM-09: 댓글·답글·좋아요를 함께 지우고 이미지는 정리 대상. 작성자만, 이미 없으면 성공. */
	@Test
	void deleteRemovesCommentsLikesAndDisposesImages() throws Exception {
		clock.set(NOW);
		AuthTestClient.Session author = member();
		AuthTestClient.Session reader = member();
		UUID image = insertUnattachedImage(author.memberId().value());
		UUID postId = postId(createPost(author, newKey(), "지울 글", List.of(image), null, INSIDE_N1));
		UUID commentId = commentId(comment(reader, postId, "댓글"));
		commentId(reply(author, commentId, "답글"));
		like(reader, postId, true).andExpect(status().isOk());

		mvc.perform(auth(delete("/api/v1/community/posts/" + postId), reader)).andExpect(status().isForbidden());
		mvc.perform(auth(delete("/api/v1/community/posts/" + postId), author)).andExpect(status().isNoContent());
		mvc.perform(auth(delete("/api/v1/community/posts/" + postId), author)).andExpect(status().isNoContent());

		assertThat(count("community_post")).isZero();
		assertThat(count("community_comment")).isZero();
		assertThat(count("community_like")).isZero();
		assertThat(imageStatus(image)).isEqualTo("DISPOSABLE");
		detail(null, postId).andExpect(status().isNotFound());
	}

}
