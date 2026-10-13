/**
 * 커뮤니티: 게시글, 이미지, 댓글·답글, 좋아요, 인기글, 관리자 삭제. (docs/to-be/05-community.md)
 * <p>
 * 공개 API: 이 패키지의 타입. 구현은 {@code internal} 하위 패키지에 두며 다른 모듈에서 접근할 수 없다.
 * 계약 정본: docs/to-be/contracts.md §2.6
 */
@ApplicationModule(displayName = "community", allowedDependencies = { "shared", "member", "neighborhood", "feeling" })
package kr.weathertalk.community;

import org.springframework.modulith.ApplicationModule;
