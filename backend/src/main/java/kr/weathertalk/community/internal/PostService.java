package kr.weathertalk.community.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.weathertalk.community.PostCreated;
import kr.weathertalk.community.internal.PostImageRepository.ImageRow;
import kr.weathertalk.community.internal.PostImageRepository.Status;
import kr.weathertalk.community.internal.PostRepository.PostRow;
import kr.weathertalk.community.internal.config.CommunityProperties;
import kr.weathertalk.feeling.FeelingApi;
import kr.weathertalk.feeling.FeelingRecordOwnership;
import kr.weathertalk.neighborhood.DeviceLocation;
import kr.weathertalk.neighborhood.Neighborhood;
import kr.weathertalk.neighborhood.NeighborhoodApi;
import kr.weathertalk.shared.Ids.FeelingRecordId;
import kr.weathertalk.shared.Ids.MemberId;
import kr.weathertalk.shared.Ids.PostId;
import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;

/**
 * 게시글 작성·수정·삭제 (FR-COM-02·03·04). 회원·약관 확인은 컨트롤러 파라미터에서 끝난다.
 * <p>
 * 작성: 본문·이미지 수 검증 → 기기 좌표로 neighborhood 현재 동네 확정(게시글의 동네, 앱이 보낸 동네 ID는 받지 않음 — BR-COM-01)
 * → 체감 기록 첨부 조건(BR-COM-06) → 한 트랜잭션에서 게시글 생성 + 이미지 첨부 + 게시글작성됨 발행(부분 성공 없음).
 */
@Service
public class PostService {

	/** @param feelingRecordId 첨부할 체감 기록(선택) */
	public record CreateCommand(String body, List<UUID> imageIds, UUID feelingRecordId, DeviceLocation location) {

		@Override
		public String toString() {
			return "CreateCommand[redacted]";
		}

	}

	/** @param imageIds 수정 후 최종 이미지 목록(순서대로) @param detachFeelingRecord 첨부된 체감 기록을 뗀다 */
	public record UpdateCommand(String body, List<UUID> imageIds, boolean detachFeelingRecord) {
	}

	private final PostRepository posts;
	private final PostImageRepository images;
	private final NeighborhoodApi neighborhoodApi;
	private final FeelingApi feelingApi;
	private final ApplicationEventPublisher events;
	private final CommunityProperties properties;
	private final Clock clock;

	PostService(PostRepository posts, PostImageRepository images, NeighborhoodApi neighborhoodApi,
			FeelingApi feelingApi, ApplicationEventPublisher events, CommunityProperties properties, Clock clock) {
		this.posts = posts;
		this.images = images;
		this.neighborhoodApi = neighborhoodApi;
		this.feelingApi = feelingApi;
		this.events = events;
		this.properties = properties;
		this.clock = clock;
	}

	@Transactional
	public PostId create(MemberId memberId, CreateCommand command) {
		String body = postBody(command.body());
		List<UUID> imageIds = imageIds(command.imageIds());
		Neighborhood neighborhood = neighborhoodApi.confirmCurrentNeighborhood(memberId, command.location())
			.orElseThrow();
		Instant now = now();
		if (command.feelingRecordId() != null) {
			checkAttachable(memberId, command.feelingRecordId(), neighborhood, now);
		}
		UUID postId = UUID.randomUUID();
		posts.insert(new PostRow(postId, memberId.value(), neighborhood.id().value(), body, command.feelingRecordId(),
				now, null));
		attachNew(memberId, postId, imageIds, List.of());
		events.publishEvent(new PostCreated(UUID.randomUUID(), memberId, new PostId(postId), neighborhood.id(), now));
		return new PostId(postId);
	}

	/** 본문·이미지·체감 기록 첨부 해제만 바꾼다(BR-COM-07). 게시글의 동네는 바뀌지 않는다. 이벤트 없음. */
	@Transactional
	public void update(MemberId memberId, UUID postId, UpdateCommand command) {
		String body = postBody(command.body());
		List<UUID> imageIds = imageIds(command.imageIds());
		PostRow post = posts.lock(postId)
			.orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "삭제되었거나 없는 게시글입니다."));
		requireAuthor(post, memberId);
		List<ImageRow> current = images.findAttached(List.of(postId)).getOrDefault(postId, List.of());
		Set<UUID> keep = new LinkedHashSet<>(imageIds);
		images.dispose(current.stream().map(ImageRow::id).filter(id -> !keep.contains(id)).toList(), now());
		attachNew(memberId, postId, imageIds, current.stream().map(ImageRow::id).toList());
		posts.updateContent(postId, body, command.detachFeelingRecord(), now());
	}

	/** 작성자 삭제(FR-COM-04). 이미 없는 게시글이면 성공(멱등). */
	@Transactional
	public void delete(MemberId memberId, UUID postId) {
		posts.lock(postId).ifPresent(post -> {
			requireAuthor(post, memberId);
			deleteCascade(post);
		});
	}

	/**
	 * 게시글과 댓글·답글(삭제된 댓글 자리 포함)·좋아요를 지우고 이미지를 정리 대상으로 돌린다(BR-COM-09).
	 * 작성자 삭제·관리자 삭제·탈퇴 정리가 같은 규칙을 쓴다(FR-COM-13·15). 파일 삭제는 정리 배치가 한다.
	 */
	void deleteCascade(PostRow post) {
		images.disposeByPost(post.id(), now());
		posts.delete(post.id());
	}

	/**
	 * 이미지 첨부(FR-COM-02 규칙 3): 이미 이 게시글에 붙은 것(수정 시)이 아니면 요청자가 올린 미첨부 이미지여야 한다.
	 * 최종 목록 순서대로 자리를 다시 매긴다.
	 */
	private void attachNew(MemberId memberId, UUID postId, List<UUID> finalIds, List<UUID> alreadyAttached) {
		if (finalIds.isEmpty()) {
			return;
		}
		Map<UUID, ImageRow> locked = images.lockAll(finalIds)
			.stream()
			.collect(Collectors.toMap(ImageRow::id, Function.identity()));
		for (UUID id : finalIds) {
			ImageRow row = locked.get(id);
			boolean mine = row != null && row.uploaderId().equals(memberId.value());
			boolean usable = mine && (alreadyAttached.contains(id) ? postId.equals(row.postId())
					: row.status() == Status.UNATTACHED);
			if (!usable) {
				throw new DomainException(ErrorCode.POST_IMAGE_NOT_ATTACHABLE,
						"붙일 수 없는 이미지가 있습니다. 직접 올린, 아직 게시글에 붙지 않은 이미지만 쓸 수 있습니다.",
						Map.of("imageId", id.toString()));
			}
		}
		for (int i = 0; i < finalIds.size(); i++) {
			images.attach(finalIds.get(i), postId, i);
		}
	}

	/** 체감 기록 첨부 조건 (BR-COM-06): 본인 기록, 같은 동네, 작성 시각 기준 community.feeling-attach-window 이내. */
	private void checkAttachable(MemberId memberId, UUID recordId, Neighborhood neighborhood, Instant now) {
		FeelingRecordOwnership ownership = feelingApi.checkOwnership(memberId, new FeelingRecordId(recordId));
		if (!ownership.owned()) {
			throw notAttachable("NOT_OWNED", "본인의 체감 기록만 첨부할 수 있습니다.");
		}
		if (!neighborhood.id().equals(ownership.neighborhoodId())) {
			throw notAttachable("NEIGHBORHOOD_MISMATCH", "게시글과 같은 동네에서 남긴 체감 기록만 첨부할 수 있습니다.");
		}
		Duration age = Duration.between(ownership.recordedAt(), now);
		if (age.compareTo(properties.feelingAttachWindow()) > 0) {
			throw notAttachable("TOO_OLD", "최근에 남긴 체감 기록만 첨부할 수 있습니다.");
		}
	}

	private String postBody(String body) {
		return TextRules.body(body, properties.post().minLength(), properties.post().maxLength(), "게시글");
	}

	private List<UUID> imageIds(List<UUID> ids) {
		if (ids == null) {
			return List.of();
		}
		List<UUID> list = new ArrayList<>(new LinkedHashSet<>(ids));
		if (list.contains(null) || list.size() != ids.size()) {
			throw new DomainException(ErrorCode.INVALID_REQUEST, "이미지 목록에 빈 값이나 중복이 있습니다.");
		}
		if (list.size() > properties.maxImages()) {
			throw new DomainException(ErrorCode.POST_IMAGE_NOT_ATTACHABLE,
					"이미지는 최대 " + properties.maxImages() + "장까지 붙일 수 있습니다.", Map.of("reason", "TOO_MANY"));
		}
		return list;
	}

	static void requireAuthor(PostRow post, MemberId memberId) {
		if (!post.authorId().equals(memberId.value())) {
			throw new DomainException(ErrorCode.NOT_CONTENT_AUTHOR, "작성자만 할 수 있습니다.");
		}
	}

	private static DomainException notAttachable(String reason, String message) {
		return new DomainException(ErrorCode.FEELING_RECORD_NOT_ATTACHABLE, message, Map.of("reason", reason));
	}

	private Instant now() {
		return clock.instant().truncatedTo(ChronoUnit.MICROS);
	}

}
