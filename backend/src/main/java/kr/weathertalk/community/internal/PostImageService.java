package kr.weathertalk.community.internal;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import kr.weathertalk.community.internal.PostImageRepository.ImageRow;
import kr.weathertalk.community.internal.PostImageRepository.Status;
import kr.weathertalk.community.internal.config.CommunityProperties;
import kr.weathertalk.community.internal.image.ImageSanitizer;
import kr.weathertalk.community.internal.image.ImageStorage;
import kr.weathertalk.community.internal.image.ImageStorage.StorageUnavailableException;
import kr.weathertalk.shared.Ids.MemberId;
import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;

/**
 * 게시글 이미지 업로드 수명주기 (FR-COM-01, FR-COM-14).
 * <ol>
 * <li>업로드 요청: 형식·크기를 확인하고 사전 서명 PUT 주소를 준다. 앱은 원본을 임시 키({@code uploads/<ID>})로 저장소에 직접 올린다.</li>
 * <li>업로드 확인: 서버가 원본을 읽어 메타데이터를 버리고 다시 인코딩·축소해 저장본 키({@code images/<ID>.jpg|png})에 쓰고 원본을 지운다.
 * 결과는 '미첨부' 이미지다. 읽을 수 없거나 크기 초과면 원본과 기록을 지운다(실패한 업로드는 아무것도 남기지 않음).</li>
 * <li>정리: 정리 대상과 보관 기간(community.image.orphan-ttl)을 넘긴 미첨부·업로드 중 이미지의 파일을 지우고 기록을 없앤다.
 * 파일 삭제가 실패하면 기록을 남겨 다음 주기에 다시 시도한다.</li>
 * </ol>
 * 서버를 거쳐 올리지 않고 사전 서명을 쓰는 이유: 홈 PC 배포에서 앱 서버 대역폭·메모리를 사진 원본이 차지하지 않게 하려고.
 * 원본은 확인 단계에서 한 번만 읽는다.
 */
@Service
public class PostImageService {

	private static final Logger log = LoggerFactory.getLogger(PostImageService.class);

	// 한 번에 지울 기록 수(정리 작업 한 회차가 너무 길어지지 않게). 남으면 다음 묶음을 이어서 처리한다.
	private static final int CLEANUP_BATCH = 200;

	/** 사전 서명 업로드 안내. 앱은 {@code uploadUrl}로 PUT하면서 {@code headers}를 그대로 붙인다. */
	public record UploadTicket(UUID imageId, String uploadUrl, Map<String, String> headers, Instant expiresAt) {
	}

	/** 업로드 확인 결과: 미첨부 이미지와 표시용 주소. */
	public record UploadedImage(UUID imageId, String url, int width, int height, String contentType) {
	}

	private final PostImageRepository images;
	private final ImageStorage storage;
	private final ImageSanitizer sanitizer;
	private final CommunityProperties properties;
	private final Clock clock;

	PostImageService(PostImageRepository images, ImageStorage storage, ImageSanitizer sanitizer,
			CommunityProperties properties, Clock clock) {
		this.images = images;
		this.storage = storage;
		this.sanitizer = sanitizer;
		this.properties = properties;
		this.clock = clock;
	}

	public UploadTicket requestUpload(MemberId memberId, String contentType, Long contentLength) {
		String type = contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
		boolean allowed = properties.image().allowedTypes().stream().anyMatch(t -> t.equalsIgnoreCase(type));
		if (!allowed) {
			throw rejected("TYPE_NOT_ALLOWED", "허용되지 않는 이미지 형식입니다. 허용: " + properties.image().allowedTypes());
		}
		if (contentLength == null || contentLength < 1) {
			throw new DomainException(ErrorCode.INVALID_REQUEST, "이미지 크기(바이트)를 보내 주세요.");
		}
		if (contentLength > properties.image().maxFileSize().toBytes()) {
			throw rejected("TOO_LARGE", "이미지는 " + properties.image().maxFileSize().toMegabytes() + "MB 이하여야 합니다.");
		}
		UUID id = UUID.randomUUID();
		String uploadKey = "uploads/" + id;
		ImageStorage.PresignedUpload presigned = withStorage(
				() -> storage.presignUpload(uploadKey, type, contentLength, properties.image().uploadUrlTtl()));
		images.insertUploading(id, memberId.value(), uploadKey, now());
		return new UploadTicket(id, presigned.url(), presigned.headers(), presigned.expiresAt());
	}

	/** 업로드 확인. 이미 확인된 이미지면 같은 결과를 돌려준다(재시도 멱등). */
	public UploadedImage complete(MemberId memberId, UUID imageId) {
		ImageRow row = images.findById(imageId)
			.filter(r -> r.uploaderId().equals(memberId.value()))
			.filter(r -> r.status() != Status.DISPOSABLE)
			.orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "업로드할 이미지가 없습니다. 다시 올려 주세요."));
		if (row.status() != Status.UPLOADING) {
			return uploaded(row.id(), row.objectKey(), row.width(), row.height(), row.contentType());
		}
		return withStorage(() -> process(row));
	}

	private UploadedImage process(ImageRow row) {
		long size = storage.size(row.uploadKey())
			.orElseThrow(() -> rejected("NOT_UPLOADED", "이미지가 아직 올라오지 않았습니다. 업로드를 마친 뒤 다시 확인해 주세요."));
		if (size > properties.image().maxFileSize().toBytes()) {
			discard(row);
			throw rejected("TOO_LARGE", "이미지는 " + properties.image().maxFileSize().toMegabytes() + "MB 이하여야 합니다.");
		}
		byte[] original = storage.read(row.uploadKey())
			.orElseThrow(() -> rejected("NOT_UPLOADED", "이미지가 아직 올라오지 않았습니다."));
		ImageSanitizer.Sanitized sanitized;
		try {
			sanitized = sanitizer.sanitize(original);
		}
		catch (ImageSanitizer.UnreadableImageException e) {
			discard(row);
			throw rejected("UNREADABLE", e.getMessage());
		}
		String objectKey = "images/" + row.id() + ("image/png".equals(sanitized.contentType()) ? ".png" : ".jpg");
		storage.write(objectKey, sanitized.content(), sanitized.contentType());
		int updated = images.markUploaded(row.id(), objectKey, sanitized.contentType(), sanitized.content().length,
				sanitized.width(), sanitized.height(), now());
		if (updated == 0) {
			// 확인 도중 보관 기간이 지나 정리 대상이 됐다(정리 배치가 저장본까지 지운다)
			throw new DomainException(ErrorCode.NOT_FOUND, "업로드 확인 기한이 지났습니다. 다시 올려 주세요.");
		}
		deleteQuietly(row.uploadKey());
		return uploaded(row.id(), objectKey, sanitized.width(), sanitized.height(), sanitized.contentType());
	}

	/** 받을 수 없는 업로드: 원본과 기록을 지운다. */
	private void discard(ImageRow row) {
		deleteQuietly(row.uploadKey());
		images.deleteRow(row.id());
	}

	private void deleteQuietly(String key) {
		try {
			storage.delete(key);
		}
		catch (StorageUnavailableException e) {
			// 원본이 남아도 기록 정리 시(정리 대상) 다시 지운다
			log.warn("업로드 원본 삭제 실패(다음 정리에서 다시 시도): {}", key);
		}
	}

	/**
	 * 정리 (FR-COM-14). 보관 기간을 넘긴 미첨부·업로드 중 이미지를 먼저 정리 대상으로 돌리고, 정리 대상의 파일과 기록을 지운다.
	 *
	 * @return 기록까지 지운 이미지 수
	 */
	public int cleanup() {
		if (!storage.configured()) {
			return 0;
		}
		Instant now = now();
		images.disposeExpired(now.minus(properties.image().orphanTtl()), now);
		int removed = 0;
		while (true) {
			List<ImageRow> batch = images.findDisposable(CLEANUP_BATCH);
			boolean failed = false;
			for (ImageRow row : batch) {
				try {
					storage.delete(row.uploadKey());
					if (row.objectKey() != null) {
						storage.delete(row.objectKey());
					}
					images.deleteDisposed(row.id());
					removed++;
				}
				catch (StorageUnavailableException e) {
					failed = true;
					log.warn("이미지 파일 삭제 실패(다음 주기에 다시 시도): {}", row.id());
				}
			}
			if (failed || batch.size() < CLEANUP_BATCH) {
				return removed;
			}
		}
	}

	/** 표시용 주소. 저장소가 설정되지 않았거나 서명할 수 없으면 null(게시글 표시는 막지 않는다). */
	String viewUrl(String objectKey) {
		if (objectKey == null || !storage.configured()) {
			return null;
		}
		try {
			return storage.presignView(objectKey, properties.image().viewUrlTtl());
		}
		catch (RuntimeException e) {
			log.warn("이미지 표시 주소 서명 실패: {}", e.getClass().getSimpleName());
			return null;
		}
	}

	private UploadedImage uploaded(UUID id, String objectKey, Integer width, Integer height, String contentType) {
		return new UploadedImage(id, viewUrl(objectKey), width == null ? 0 : width, height == null ? 0 : height,
				contentType);
	}

	private <T> T withStorage(java.util.function.Supplier<T> action) {
		try {
			return action.get();
		}
		catch (StorageUnavailableException e) {
			log.warn("사진 저장소 사용 불가: {}", e.getMessage());
			throw new DomainException(ErrorCode.TEMPORARILY_UNAVAILABLE, "사진 저장소를 잠시 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.");
		}
	}

	private Instant now() {
		return clock.instant().truncatedTo(ChronoUnit.MICROS);
	}

	private static DomainException rejected(String reason, String message) {
		return new DomainException(ErrorCode.IMAGE_REJECTED, message, Map.of("reason", reason));
	}

}
