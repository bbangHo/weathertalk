package kr.weathertalk.community.internal;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import kr.weathertalk.member.MemberWithdrawn;

/**
 * 회원탈퇴함 구독 (FR-COM-13, BR-COM-14, legacy 03-#23). 한 트랜잭션에서 그 회원의
 * <ol>
 * <li>게시글을 작성자 삭제와 같이 지운다(남의 댓글·좋아요 포함, 이미지는 정리 대상).</li>
 * <li>남의 게시글에 단 답글 → 댓글 순으로 댓글 삭제 규칙대로 지운다(답글이 남은 댓글은 삭제된 댓글 자리로).</li>
 * <li>좋아요를 모두 지운다.</li>
 * <li>올렸지만 게시글에 붙지 않은 이미지를 정리 대상으로 돌린다.</li>
 * </ol>
 * 이벤트를 발행하지 않는다. 지우는 것뿐이라 다시 전달돼도 결과가 같고(멱등), 실패하면 이벤트 발행 저장소의 재발행으로 다시 실행된다.
 */
@Component
class CommunityMemberWithdrawalCleaner {

	private final PostRepository posts;
	private final CommentRepository comments;
	private final LikeRepository likes;
	private final PostImageRepository images;
	private final PostService postService;
	private final CommentService commentService;
	private final Clock clock;

	CommunityMemberWithdrawalCleaner(PostRepository posts, CommentRepository comments, LikeRepository likes,
			PostImageRepository images, PostService postService, CommentService commentService, Clock clock) {
		this.posts = posts;
		this.comments = comments;
		this.likes = likes;
		this.images = images;
		this.postService = postService;
		this.commentService = commentService;
		this.clock = clock;
	}

	/** 익명으로 남긴 글·댓글의 작성자. 실제 회원 ID와 겹치지 않는다. 조회에서는 "탈퇴한 이웃"으로 보인다. */
	static final UUID ANONYMOUS_AUTHOR = new UUID(0, 0);

	@ApplicationModuleListener
	void on(MemberWithdrawn event) {
		UUID memberId = event.memberId().value();
		if (event.keepCommunityContent()) {
			// 카카오 연결 해제 웹훅 탈퇴: 지우지 않고 회원과의 연결만 끊는다. 다시 전달되면 바꿀 행이 없다(멱등).
			posts.anonymizeAuthor(memberId, ANONYMOUS_AUTHOR);
			comments.anonymizeAuthor(memberId, ANONYMOUS_AUTHOR);
			likes.anonymizeMember(memberId);
			images.disposeUnattachedByUploader(memberId, clock.instant().truncatedTo(ChronoUnit.MICROS));
			return;
		}
		posts.findByAuthor(memberId).forEach(postService::deleteCascade);
		// 답글을 먼저 지워야, 그 회원의 댓글에 남은 답글이 그 회원 것뿐일 때 자리 없이 지워진다
		comments.findReplyIdsByAuthor(memberId)
			.forEach(id -> comments.findById(id).ifPresent(commentService::remove));
		comments.findTopLevelIdsByAuthor(memberId)
			.forEach(id -> comments.findById(id).ifPresent(commentService::remove));
		likes.deleteByMember(memberId);
		images.disposeUnattachedByUploader(memberId, clock.instant().truncatedTo(ChronoUnit.MICROS));
	}

}
