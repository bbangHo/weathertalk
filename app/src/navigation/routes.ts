// 화면 경로 단일 정의 (07 §4, §7.3: 푸시 이동도 같은 식별자를 쓴다)
// 파일 구조(src/app/)를 바꾸면 이 파일과 함께 바꾼다.
import type { Href } from 'expo-router';

/** 홈에서 강조할 섹션 (07 §7.3) */
export type HomeFocus = 'hourly' | 'field-report';

export const routes = {
  /** SCR-01 홈. neighborhoodId가 있으면 그 동네를 보는 동네로 연다. */
  home: (params: { neighborhoodId?: string; focus?: HomeFocus } = {}): Href => withParams('/', params),
  /** SCR-06 커뮤니티 피드 */
  community: (): Href => '/community',
  /** SCR-07 게시글 상세 */
  postDetail: (postId: string, params: { focus?: 'comments' } = {}): Href =>
    withParams(`/community/${encodeURIComponent(postId)}`, params),
  /** SCR-09 마이페이지 */
  my: (): Href => '/my',
  /** SCR-11 알림 구독 설정 */
  notificationSettings: (): Href => '/my/notifications',
  /** SCR-02 관심 동네 관리 */
  interestNeighborhoods: (): Href => '/my/interest-neighborhoods',
  /** SCR-13 내가 쓴 글 */
  myPosts: (): Href => '/my/posts',
  /** SCR-14 내 체감 기록 */
  myFeelingRecords: (): Href => '/my/feeling-records',

  // ── 루트 모달 (어느 탭에서나 같은 경로로 연다, 07 §4-1) ──
  /** SCR-02 동네 전환 시트 */
  neighborhoodSwitch: (): Href => '/neighborhood-switch',
  /** SCR-03 동네 검색. fromOnboarding이면 선택 즉시 보는 동네로 정한다. */
  neighborhoodSearch: (params: { fromOnboarding?: boolean } = {}): Href =>
    withParams('/neighborhood-search', { fromOnboarding: params.fromOnboarding ? '1' : undefined }),
  /** SCR-04 체감 기록 작성 */
  feelingRecord: (): Href => '/feeling-record',
  /** SCR-05 현장 제보 */
  fieldReport: (): Href => '/field-report',
  /** SCR-08 게시글 작성·수정. postId가 있으면 수정. */
  postEditor: (params: { postId?: string; feelingRecordId?: string } = {}): Href =>
    withParams('/post-editor', params),
  /** SCR-10 가입 */
  signup: (params: { mode?: 'reagree' } = {}): Href => withParams('/signup', params),
  /** SCR-12 첫 실행 안내 */
  welcome: (): Href => '/welcome',
} as const;

function withParams(pathname: string, params: Record<string, string | undefined>): Href {
  const defined = Object.fromEntries(Object.entries(params).filter(([, value]) => value !== undefined)) as Record<
    string,
    string
  >;
  return Object.keys(defined).length === 0 ? (pathname as Href) : ({ pathname, params: defined } as Href);
}

/** 푸시 데이터의 이동 대상 키 (backend notification NotificationKind.navigationTarget) */
export type PushTarget = 'home' | 'home.hourly' | 'home.field-report' | 'post.detail';

/**
 * 알림 데이터 → 이동 경로 (07 §7.3). 알 수 없는 종류·필드 누락은 홈으로 연다.
 * 데이터 필드: target, neighborhoodId(동네 알림) 또는 postId(댓글 알림).
 */
export function hrefForPushData(data: Record<string, unknown> | null | undefined): Href {
  const target = typeof data?.target === 'string' ? data.target : null;
  const neighborhoodId = typeof data?.neighborhoodId === 'string' ? data.neighborhoodId : undefined;
  const postId = typeof data?.postId === 'string' ? data.postId : undefined;

  switch (target) {
    case 'home':
      return routes.home({ neighborhoodId });
    case 'home.hourly':
      return routes.home({ neighborhoodId, focus: 'hourly' });
    case 'home.field-report':
      return routes.home({ neighborhoodId, focus: 'field-report' });
    case 'post.detail':
      return postId ? routes.postDetail(postId, { focus: 'comments' }) : routes.home();
    default:
      return routes.home();
  }
}
