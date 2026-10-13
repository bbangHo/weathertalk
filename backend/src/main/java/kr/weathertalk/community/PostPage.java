package kr.weathertalk.community;

import java.util.List;

/**
 * 게시글 목록 한 페이지 (BR-COM-08). 목록 크기는 실제 항목 수다(legacy 03-#2).
 *
 * @param nextCursor 다음 페이지 커서. 더 없으면 null. 같은 커서로 다시 조회하면 그 사이 새 글·삭제가 있어도 중복·누락 없이 이어진다
 */
public record PostPage(List<PostSummary> items, String nextCursor) {
}
