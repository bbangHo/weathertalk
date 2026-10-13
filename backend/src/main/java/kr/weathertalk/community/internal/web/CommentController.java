package kr.weathertalk.community.internal.web;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import kr.weathertalk.community.internal.CommentService;
import kr.weathertalk.community.internal.PostQueryService;
import kr.weathertalk.community.internal.config.CommunityProperties;
import kr.weathertalk.member.Requester;
import kr.weathertalk.member.RequiresAgreedTerms;
import kr.weathertalk.shared.idempotency.IdempotencyKey;
import kr.weathertalk.shared.idempotency.IdempotentExecutor;

/**
 * 댓글·답글 웹 API (FR-COM-07~10). 작성은 요청 식별자 멱등(BR-COM-12). 게시글의 동네와 관계없이 쓸 수 있어 좌표를 받지 않는다(BR-COM-02).
 * 본인 댓글 삭제는 약관 재동의 전에도 허용, 작성·수정은 막는다(contracts.md §4).
 */
@RestController
class CommentController {

	record BodyRequest(String body) {
	}

	record CommentCreatedResponse(UUID commentId) {
	}

	private final CommentService comments;
	private final PostQueryService queries;
	private final IdempotentExecutor idempotent;
	private final CommunityProperties properties;

	CommentController(CommentService comments, PostQueryService queries, IdempotentExecutor idempotent,
			CommunityProperties properties) {
		this.comments = comments;
		this.queries = queries;
		this.idempotent = idempotent;
		this.properties = properties;
	}

	/** FR-COM-10 댓글 목록(다음 페이지). 첫 페이지는 상세 조회에 들어 있다. */
	@GetMapping("/api/v1/community/posts/{postId}/comments")
	PostQueryService.CommentPage list(Requester requester, @PathVariable UUID postId,
			@RequestParam(required = false) String cursor) {
		return queries.comments(postId, requester, cursor);
	}

	/** FR-COM-07 댓글 작성. */
	@PostMapping("/api/v1/community/posts/{postId}/comments")
	CommentCreatedResponse comment(@RequiresAgreedTerms Requester.Member member, IdempotencyKey key,
			@PathVariable UUID postId, @RequestBody BodyRequest request) {
		return idempotent.execute(member.memberId(), "community.comment.create", key, properties.idempotencyWindow(),
				CommentCreatedResponse.class,
				() -> new CommentCreatedResponse(comments.comment(member.memberId(), postId, request.body()).value()));
	}

	/** FR-COM-08 답글 작성(상위 댓글 아래). */
	@PostMapping("/api/v1/community/comments/{commentId}/replies")
	CommentCreatedResponse reply(@RequiresAgreedTerms Requester.Member member, IdempotencyKey key,
			@PathVariable UUID commentId, @RequestBody BodyRequest request) {
		return idempotent.execute(member.memberId(), "community.reply.create", key, properties.idempotencyWindow(),
				CommentCreatedResponse.class,
				() -> new CommentCreatedResponse(comments.reply(member.memberId(), commentId, request.body()).value()));
	}

	/** FR-COM-09 수정(본문만). */
	@PatchMapping("/api/v1/community/comments/{commentId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void update(@RequiresAgreedTerms Requester.Member member, @PathVariable UUID commentId,
			@RequestBody BodyRequest request) {
		comments.update(member.memberId(), commentId, request.body());
	}

	/** FR-COM-09 삭제. 이미 없으면 성공. */
	@DeleteMapping("/api/v1/community/comments/{commentId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(Requester.Member member, @PathVariable UUID commentId) {
		comments.delete(member.memberId(), commentId);
	}

}
