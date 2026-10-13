package kr.weathertalk.community;

import java.util.List;

import kr.weathertalk.member.Requester;
import kr.weathertalk.shared.Ids.MemberId;
import kr.weathertalk.shared.Ids.NeighborhoodId;

/**
 * community 모듈의 공개 인터페이스 (contracts.md §2.6). 다른 모듈은 이 타입으로만 community 기능을 사용한다.
 * <p>
 * 게시글·댓글·좋아요·이미지 쓰기와 피드·상세 조회는 앱 요청 명령이라 공개 인터페이스에 두지 않는다.
 */
public interface CommunityApi {

	/**
	 * 동네 인기글 조회 (FR-COM-12, BR-COM-13). 조건을 채우는 글이 모자라면 모자란 대로(0건 포함) 돌려준다 — 채울지는 home이 정한다.
	 *
	 * @param requester 조회자. 회원이면 항목에 좋아요 여부·본인 글 여부가 붙는다
	 */
	List<PostSummary> findPopularPosts(NeighborhoodId neighborhoodId, Requester requester);

	/**
	 * 회원의 게시글 목록 조회 (FR-COM-16, 마이페이지 '내가 쓴 글'). 모든 동네, 피드와 같은 정렬·커서(BR-COM-08).
	 *
	 * @param cursor 직전 페이지의 {@link PostPage#nextCursor()}. 없으면 첫 페이지
	 * @param size 없으면 community.feed.page-size, 최대 community.feed.max-page-size로 자른다
	 * @throws kr.weathertalk.shared.error.DomainException INVALID_REQUEST — 형식이 잘못된 커서
	 */
	PostPage findMemberPosts(MemberId memberId, String cursor, Integer size);

}
