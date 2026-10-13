package kr.weathertalk.community;

import static kr.weathertalk.neighborhood.NeighborhoodTestData.INSIDE_N1;
import static kr.weathertalk.weather.WeatherStubs.kst;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.tiff.constants.TiffTagConstants;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.GpsDirectory;
import com.jayway.jsonpath.JsonPath;

import kr.weathertalk.AuthTestClient;
import kr.weathertalk.IntegrationTest;
import kr.weathertalk.community.internal.PostImageService;
import kr.weathertalk.community.internal.image.ImageStorage;

/**
 * 게시글 이미지 업로드·정리 (FR-COM-01·14, BR-COM-04, ADR 0009) — 실제 SeaweedFS(S3 API) 컨테이너로 사전 서명 업로드부터 확인한다.
 * 업로드 확인 때 서버가 다시 인코딩해 촬영 위치(EXIF GPS)를 포함한 메타데이터를 지우고, 긴 변을 community.image.max-dimension으로 줄인다.
 */
@IntegrationTest
class ImageUploadTests extends CommunityTestBase {

	static final Instant NOW = kst("2026-10-13T09:15");

	static final HttpClient HTTP = HttpClient.newHttpClient();

	@Autowired
	ImageStorage storage;

	@Autowired
	PostImageService imageService;

	/** 촬영 위치(GPS)와 방향 값이 들어간 JPEG. */
	static byte[] jpegWithLocation(int width, int height, int orientation) throws Exception {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(Color.ORANGE);
		g.fillRect(0, 0, width, height);
		g.setColor(Color.BLUE);
		g.fillRect(0, 0, width / 3, height / 3);
		g.dispose();
		ByteArrayOutputStream plain = new ByteArrayOutputStream();
		ImageIO.write(image, "jpeg", plain);
		TiffOutputSet exif = new TiffOutputSet();
		exif.setGpsInDegrees(127.0276, 37.4979);
		exif.getOrCreateRootDirectory().add(TiffTagConstants.TIFF_TAG_ORIENTATION, (short) orientation);
		ByteArrayOutputStream tagged = new ByteArrayOutputStream();
		new ExifRewriter().updateExifMetadataLossless(plain.toByteArray(), tagged, exif);
		return tagged.toByteArray();
	}

	static Metadata metadata(byte[] bytes) throws Exception {
		return ImageMetadataReader.readMetadata(new ByteArrayInputStream(bytes));
	}

	private ResultActions requestUpload(AuthTestClient.Session session, String contentType, long length)
			throws Exception {
		return mvc.perform(auth(post("/api/v1/community/images"), session).contentType(MediaType.APPLICATION_JSON)
			.content("{\"contentType\":\"" + contentType + "\",\"contentLength\":" + length + "}"));
	}

	/** 앱처럼: 사전 서명 주소에 서명된 헤더를 붙여 직접 PUT. */
	private UUID putToStorage(AuthTestClient.Session session, byte[] content, String contentType) throws Exception {
		String ticket = body(requestUpload(session, contentType, content.length).andExpect(status().isOk()));
		Map<String, String> headers = JsonPath.read(ticket, "$.headers");
		HttpRequest.Builder put = HttpRequest.newBuilder(URI.create(JsonPath.read(ticket, "$.uploadUrl")))
			.PUT(HttpRequest.BodyPublishers.ofByteArray(content));
		// Content-Length는 HTTP 클라이언트가 본문 크기로 채운다(서명된 값과 같음)
		headers.forEach((name, value) -> {
			if (!"content-length".equalsIgnoreCase(name)) {
				put.header(name, value);
			}
		});
		HttpResponse<String> response = HTTP.send(put.build(), HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		return UUID.fromString(JsonPath.read(ticket, "$.imageId"));
	}

	private ResultActions complete(AuthTestClient.Session session, UUID imageId) throws Exception {
		return mvc.perform(auth(post("/api/v1/community/images/" + imageId + "/complete"), session));
	}

	private UUID upload(AuthTestClient.Session session, byte[] content) throws Exception {
		UUID id = putToStorage(session, content, "image/jpeg");
		complete(session, id).andExpect(status().isOk());
		return id;
	}

	private static byte[] download(String url) throws Exception {
		HttpResponse<byte[]> response = HTTP.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
				HttpResponse.BodyHandlers.ofByteArray());
		assertThat(response.statusCode()).isEqualTo(200);
		return response.body();
	}

	private String column(UUID imageId, String column) {
		return jdbc.sql("SELECT " + column + " FROM community_post_image WHERE id = :id")
			.param("id", imageId)
			.query(String.class)
			.single();
	}

	/** 사전 서명 업로드 → 업로드 확인 → 저장본에 위치·EXIF 없음, 긴 변 축소, 원본(임시 키) 삭제. 게시글에 붙여 표시 주소로 받는다. */
	@Test
	void uploadedPhotoLosesLocationMetadataAndIsResized() throws Exception {
		clock.set(NOW);
		AuthTestClient.Session session = member();
		byte[] original = jpegWithLocation(3000, 2000, 1);
		assertThat(metadata(original).getFirstDirectoryOfType(GpsDirectory.class)).isNotNull();
		assertThat(metadata(original).getFirstDirectoryOfType(GpsDirectory.class).getGeoLocation()).isNotNull();

		UUID imageId = putToStorage(session, original, "image/jpeg");
		String uploadKey = column(imageId, "upload_key");
		assertThat(storage.size(uploadKey)).contains((long) original.length);

		String completed = body(complete(session, imageId).andExpect(status().isOk())
			.andExpect(jsonPath("$.width").value(2048))
			.andExpect(jsonPath("$.height").value(1365))
			.andExpect(jsonPath("$.contentType").value("image/jpeg")));
		byte[] stored = download(JsonPath.read(completed, "$.url"));
		Metadata sanitized = metadata(stored);
		assertThat(sanitized.getFirstDirectoryOfType(GpsDirectory.class)).isNull();
		assertThat(sanitized.getFirstDirectoryOfType(ExifIFD0Directory.class)).isNull();
		BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(stored));
		assertThat(Math.max(decoded.getWidth(), decoded.getHeight())).isEqualTo(2048);
		assertThat(storage.size(uploadKey)).isEmpty();
		assertThat(imageStatus(imageId)).isEqualTo("UNATTACHED");
		// 확인을 다시 불러도 같은 결과(재시도 멱등)
		complete(session, imageId).andExpect(status().isOk()).andExpect(jsonPath("$.width").value(2048));

		UUID postId = postId(createPost(session, newKey(), "사진 글", List.of(imageId), null, INSIDE_N1));
		String detail = body(detail(null, postId).andExpect(jsonPath("$.post.images[0].imageId").value(imageId.toString())));
		assertThat(download(JsonPath.read(detail, "$.post.images[0].url"))).isEqualTo(stored);
	}

	/** 방향 값(시계 방향 90도)은 버리기 전에 픽셀에 반영한다 — 가로로 저장된 세로 사진이 세로로 나온다. */
	@Test
	void exifOrientationIsAppliedBeforeMetadataIsDropped() throws Exception {
		clock.set(NOW);
		AuthTestClient.Session session = member();
		UUID imageId = putToStorage(session, jpegWithLocation(300, 200, 6), "image/jpeg");
		complete(session, imageId).andExpect(status().isOk())
			.andExpect(jsonPath("$.width").value(200))
			.andExpect(jsonPath("$.height").value(300));
	}

	/** 형식·크기는 주소 발급 때 거른다. 이미지가 아닌 파일·올리지 않은 이미지는 확인 때 거르고, 실패한 업로드는 아무것도 남기지 않는다. */
	@Test
	void invalidUploadsAreRejectedAndLeaveNothing() throws Exception {
		clock.set(NOW);
		AuthTestClient.Session session = member();
		requestUpload(session, "image/gif", 100).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("IMAGE_REJECTED"))
			.andExpect(jsonPath("$.reason").value("TYPE_NOT_ALLOWED"));
		requestUpload(session, "image/heic", 100).andExpect(jsonPath("$.reason").value("TYPE_NOT_ALLOWED"));
		requestUpload(session, "image/jpeg", 10L * 1024 * 1024 + 1).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.reason").value("TOO_LARGE"));
		mvc.perform(post("/api/v1/community/images").contentType(MediaType.APPLICATION_JSON)
			.content("{\"contentType\":\"image/jpeg\",\"contentLength\":100}")).andExpect(status().isUnauthorized());

		String ticket = body(requestUpload(session, "image/jpeg", 100).andExpect(status().isOk()));
		UUID notUploaded = UUID.fromString(JsonPath.read(ticket, "$.imageId"));
		complete(session, notUploaded).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.reason").value("NOT_UPLOADED"));

		byte[] notAnImage = "이건 사진이 아니라 글자입니다".repeat(20).getBytes(java.nio.charset.StandardCharsets.UTF_8);
		UUID fake = putToStorage(session, notAnImage, "image/jpeg");
		String uploadKey = column(fake, "upload_key");
		complete(session, fake).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.reason").value("UNREADABLE"));
		assertThat(imageStatus(fake)).isNull();
		assertThat(storage.size(uploadKey)).isEmpty();
		// 남이 올린 이미지는 확인할 수 없다
		complete(member(), notUploaded).andExpect(status().isNotFound());
	}

	/**
	 * FR-COM-14: 정리 대상(게시글 삭제로 빠진 이미지)은 다음 정리에서, 미첨부 이미지는 community.image.orphan-ttl(24시간)이 지나면
	 * 파일과 기록을 지운다. 게시 중인 글의 이미지는 건드리지 않는다.
	 */
	@Test
	void cleanupRemovesDisposedAndOrphanImagesButKeepsAttachedOnes() throws Exception {
		clock.set(NOW);
		AuthTestClient.Session session = member();
		byte[] photo = jpegWithLocation(64, 48, 1);
		UUID orphan = upload(session, photo);
		UUID ofDeletedPost = upload(session, photo);
		UUID ofLivePost = upload(session, photo);
		UUID deletedPost = postId(createPost(session, newKey(), "지울 글", List.of(ofDeletedPost), null, INSIDE_N1));
		postId(createPost(session, newKey(), "남길 글", List.of(ofLivePost), null, INSIDE_N1));
		mvc.perform(auth(delete("/api/v1/community/posts/" + deletedPost), session)).andExpect(status().isNoContent());
		String disposedKey = column(ofDeletedPost, "object_key");
		String orphanKey = column(orphan, "object_key");
		String liveKey = column(ofLivePost, "object_key");

		imageService.cleanup();
		assertThat(imageStatus(ofDeletedPost)).isNull();
		assertThat(storage.size(disposedKey)).isEmpty();
		assertThat(imageStatus(orphan)).isEqualTo("UNATTACHED");
		assertThat(storage.size(orphanKey)).isPresent();

		clock.advance(Duration.ofHours(24).plusSeconds(1));
		imageService.cleanup();
		assertThat(imageStatus(orphan)).isNull();
		assertThat(storage.size(orphanKey)).isEmpty();
		assertThat(imageStatus(ofLivePost)).isEqualTo("ATTACHED");
		assertThat(storage.size(liveKey)).isPresent();
	}

}
