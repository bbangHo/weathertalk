package kr.weathertalk.community;

import static kr.weathertalk.neighborhood.NeighborhoodTestData.N1;
import static kr.weathertalk.weather.WeatherStubs.kst;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.modulith.test.Scenario;

import kr.weathertalk.AuthTestClient;
import kr.weathertalk.IntegrationTest;
import kr.weathertalk.notification.RecordingPushSender;

/**
 * 댓글작성됨 발행 → notification 댓글 알림 (contracts §3, FR-NOTI-19). 실제 API로 댓글을 달면 이벤트 발행 저장소를 거쳐
 * 게시글 작성자에 연결된 기기로 푸시가 간다(댓글 작성자 본인 기기에는 가지 않는다).
 */
@IntegrationTest
class CommentAlertScenarioTests extends CommunityTestBase {

	@Autowired
	RecordingPushSender pushes;

	@AfterEach
	void cleanUp() {
		jdbc.sql("DELETE FROM noti_device").update();
		pushes.clear();
	}

	private String device(AuthTestClient.Session session, String token) throws Exception {
		mvc.perform(post("/api/v1/notification/devices/confirm").header(HttpHeaders.AUTHORIZATION, session.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"platform\":\"IOS\",\"pushToken\":\"" + token + "\",\"osPermission\":\"GRANTED\"}"))
			.andExpect(status().isOk());
		return token;
	}

	@Test
	void commentByNeighborNotifiesPostAuthorDevice(Scenario scenario) throws Exception {
		pushes.clear();
		clock.set(kst("2026-10-13T12:00"));
		AuthTestClient.Session author = member();
		AuthTestClient.Session neighbor = member();
		String authorToken = device(author, "author-" + UUID.randomUUID());
		String neighborToken = device(neighbor, "neighbor-" + UUID.randomUUID());
		UUID postId = insertPost(author.memberId().value(), N1, clock.instant(), UUID.randomUUID());

		scenario.stimulate(() -> {
			try {
				commentId(comment(neighbor, postId, "우산 챙기세요"));
			}
			catch (Exception e) {
				throw new IllegalStateException(e);
			}
		})
			.andWaitAtMost(Duration.ofSeconds(10))
			.andWaitForStateChange(() -> pushes.sentTo(authorToken), sent -> !sent.isEmpty())
			.andVerify(sent -> {
				assertThat(sent).singleElement().satisfies(m -> {
					assertThat(m.title()).isEqualTo("내 글에 새 댓글이 달렸어요");
					assertThat(m.body()).doesNotContain("우산");
					assertThat(m.data()).containsEntry("postId", postId.toString());
				});
				assertThat(pushes.sentTo(neighborToken)).isEmpty();
			});
	}

}
