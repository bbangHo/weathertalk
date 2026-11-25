package kr.weathertalk.community.internal.web;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import kr.weathertalk.community.PostPage;
import kr.weathertalk.community.PostSummary;
import kr.weathertalk.community.internal.LikeService;
import kr.weathertalk.community.internal.PostQueryService;
import kr.weathertalk.community.internal.PostService;
import kr.weathertalk.community.internal.config.CommunityProperties;
import kr.weathertalk.member.Requester;
import kr.weathertalk.member.RequiresAgreedTerms;
import kr.weathertalk.shared.Ids.NeighborhoodId;
import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;
import kr.weathertalk.shared.idempotency.IdempotencyKey;
import kr.weathertalk.shared.idempotency.IdempotentExecutor;

/**
 * 게시글 웹 API (05 §4). 쓰기는 회원 전용 + 약관 재동의 가드, 작성은 요청 식별자 멱등(BR-COM-12, community.idempotency-window).
 * 본인 글 삭제는 약관 재동의 전에도 허용한다(contracts.md §4). 조회는 비회원도 할 수 있고 동네 제한이 없다(BR-COM-15).
 * 좌표는 받지 않고, 게시글의 동네는 앱이 기기 안에서 판정한 현재 동네 ID로 정한다(BR-COM-01, ADR 0009).
 */
@RestController
class PostController {

	record CreateRequest(String body, List<UUID> imageIds, UUID feelingRecordId,
			String neighborhoodId) {

		@Override
		public String toString() {
			return "CreateRequest[redacted]";
		}

	}

	record UpdateRequest(String body, List<UUID> imageIds, Boolean detachFeelingRecord) {
	}

	record LikeRequest(Boolean liked) {
	}

	record PostCreatedResponse(UUID postId) {
	}

	private final PostService postService;
	private final PostQueryService queries;
	private final LikeService likeService;
	private final IdempotentExecutor idempotent;
	private final CommunityProperties properties;

	PostController(PostService postService, PostQueryService queries, LikeService likeService,
			IdempotentExecutor idempotent, CommunityProperties properties) {
		this.postService = postService;
		this.queries = queries;
		this.likeService = likeService;
		this.idempotent = idempotent;
		this.properties = properties;
	}

	/** FR-COM-02 게시글 작성. 같은 요청 식별자의 재전송에는 처음 결과를 돌려준다. */
	@PostMapping("/api/v1/community/posts")
	PostCreatedResponse create(@RequiresAgreedTerms Requester.Member member, IdempotencyKey key,
			@RequestBody CreateRequest request) {
		return idempotent.execute(member.memberId(), "community.post.create", key, properties.idempotencyWindow(),
				PostCreatedResponse.class,
				() -> new PostCreatedResponse(postService
					.create(member.memberId(), new PostService.CreateCommand(request.body(), request.imageIds(),
							request.feelingRecordId(),
							new NeighborhoodId(request.neighborhoodId())))
					.value()));
	}

	/** FR-COM-03 게시글 수정(본문·이미지·체감 기록 첨부 해제). 수정된 게시글을 돌려준다. */
	@PutMapping("/api/v1/community/posts/{postId}")
	PostSummary update(@RequiresAgreedTerms Requester.Member member, @PathVariable UUID postId,
			@RequestBody UpdateRequest request) {
		postService.update(member.memberId(), postId, new PostService.UpdateCommand(request.body(), request.imageIds(),
				Boolean.TRUE.equals(request.detachFeelingRecord())));
		return queries.detail(postId, member).post();
	}

	/** FR-COM-04 게시글 삭제. 이미 없으면 성공. */
	@DeleteMapping("/api/v1/community/posts/{postId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(Requester.Member member, @PathVariable UUID postId) {
		postService.delete(member.memberId(), postId);
	}

	/** FR-COM-05 피드. */
	@GetMapping("/api/v1/community/neighborhoods/{neighborhoodId}/posts")
	PostPage feed(Requester requester, @PathVariable String neighborhoodId,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer size) {
		return queries.feed(new NeighborhoodId(neighborhoodId), requester, cursor, size);
	}

	/** FR-COM-12 동네 인기글. */
	@GetMapping("/api/v1/community/neighborhoods/{neighborhoodId}/popular-posts")
	List<PostSummary> popular(Requester requester, @PathVariable String neighborhoodId) {
		return queries.findPopularPosts(new NeighborhoodId(neighborhoodId), requester);
	}

	/** FR-COM-06 게시글 상세(댓글 첫 페이지 포함). */
	@GetMapping("/api/v1/community/posts/{postId}")
	PostQueryService.PostDetail detail(Requester requester, @PathVariable UUID postId) {
		return queries.detail(postId, requester);
	}

	/** FR-COM-16 마이페이지 '내가 쓴 글'. 조회라 약관 재동의 전에도 허용. */
	@GetMapping("/api/v1/members/me/posts")
	PostPage myPosts(Requester.Member member, @RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer size) {
		return queries.findMemberPosts(member.memberId(), cursor, size);
	}

	/** FR-COM-11 좋아요 설정·해제 — 원하는 최종 상태를 받는다(토글 아님). */
	@PutMapping("/api/v1/community/posts/{postId}/like")
	LikeService.LikeState like(@RequiresAgreedTerms Requester.Member member, @PathVariable UUID postId,
			@RequestBody LikeRequest request) {
		if (request.liked() == null) {
			throw new DomainException(ErrorCode.INVALID_REQUEST, "원하는 좋아요 상태(liked)를 보내 주세요.");
		}
		return likeService.set(member.memberId(), postId, request.liked());
	}

}
