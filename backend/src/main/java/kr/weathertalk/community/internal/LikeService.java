package kr.weathertalk.community.internal;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.weathertalk.community.PostLiked;
import kr.weathertalk.community.internal.PostRepository.PostRow;
import kr.weathertalk.shared.Ids.MemberId;
import kr.weathertalk.shared.Ids.PostId;
import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;

/**
 * 좋아요 설정·해제 (FR-COM-11). 토글이 아니라 원하는 최종 상태를 받으므로 같은 요청을 여러 번·동시에 보내도 결과가 같다(BR-COM-11).
 * 동시 삽입은 저장소 유일성(ON CONFLICT DO NOTHING)이 하나로 줄이고, 실제로 '아님 → 좋아요'가 된 요청만 좋아요함을 발행한다.
 * 게시글의 동네와 관계없이 할 수 있다(BR-COM-02).
 */
@Service
public class LikeService {

	/** 최종 좋아요 여부와 현재 좋아요 수 — 앱이 목록을 다시 받지 않고 화면을 고친다(spec/07 SCR-07). */
	public record LikeState(boolean liked, int likeCount) {
	}

	private final LikeRepository likes;
	private final PostRepository posts;
	private final ApplicationEventPublisher events;
	private final Clock clock;

	LikeService(LikeRepository likes, PostRepository posts, ApplicationEventPublisher events, Clock clock) {
		this.likes = likes;
		this.posts = posts;
		this.events = events;
		this.clock = clock;
	}

	@Transactional
	public LikeState set(MemberId memberId, UUID postId, boolean liked) {
		PostRow post = posts.findById(postId)
			.orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "삭제되었거나 없는 게시글입니다."));
		if (liked) {
			if (post.authorId().equals(memberId.value())) {
				throw new DomainException(ErrorCode.SELF_LIKE_NOT_ALLOWED, "내 게시글에는 좋아요할 수 없습니다.");
			}
			Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
			int inserted;
			try {
				inserted = likes.insertIfAbsent(postId, memberId.value(), now);
			}
			catch (DataIntegrityViolationException e) {
				// 확인 직후 게시글이 지워졌다(외래 키)
				throw new DomainException(ErrorCode.NOT_FOUND, "삭제되었거나 없는 게시글입니다.");
			}
			if (inserted == 1) {
				events.publishEvent(new PostLiked(UUID.randomUUID(), memberId, new PostId(postId), now));
			}
		}
		else {
			likes.delete(postId, memberId.value());
		}
		return new LikeState(liked, likes.count(postId));
	}

}
