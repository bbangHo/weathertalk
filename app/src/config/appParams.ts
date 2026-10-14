// 앱 설정 파라미터 기본값 (07 §1.6). 원격 전달 방식은 미결(07 §9-6)이라 여기서만 모아 둔다.

const MINUTE = 60 * 1000;
const DAY = 24 * 60 * MINUTE;

export type AppParams = {
  /** app.home-stale-after: 포그라운드 복귀 시 홈 재조회 기준 경과 시간(ms) */
  homeStaleAfterMs: number;
  /** app.location-refresh-interval: 현재 동네 재판정 최소 간격(ms) */
  locationRefreshIntervalMs: number;
  /** app.write-location-max-age: 쓰기 직전 현재 동네 판정의 최대 경과 시간(ms) */
  writeLocationMaxAgeMs: number;
  /** app.permission-preprompt-cooldown: 권한 사전 안내 거절 후 재노출 간격(ms) */
  permissionPrepromptCooldownMs: number;
  /** neighborhood.max-interest: 관심 동네 최대 개수(회원·비회원 공통, 02 BR-NBR-08). 서버 값과 같게 둔다. */
  maxInterestNeighborhoods: number;
  /** neighborhood.search-min-length: 동네 이름 검색 최소 글자 수(02 FR-NBR-07) */
  neighborhoodSearchMinLength: number;
  /** community.max-images: 게시글 이미지 최대 장수(05 §7) */
  maxPostImages: number;
  /** community.post.min-length: 본문 최소 길이(공백 제외, 코드 포인트) */
  postMinLength: number;
  /** community.post.max-length: 본문 최대 길이(앞뒤 공백 제외, 코드 포인트) */
  postMaxLength: number;
  /** community.comment.max-length: 댓글·답글 최대 길이 */
  commentMaxLength: number;
  /** community.image.max-file-size: 이미지 한 장 최대 크기(바이트) */
  imageMaxFileSize: number;
  /** community.image.max-dimension: 서버 저장 긴 변. 앱도 올리기 전에 이 크기로 줄여 전송량을 아낀다 */
  imageMaxDimension: number;
  /** community.feed.page-size: 피드·내가 쓴 글 한 페이지 개수 */
  feedPageSize: number;
  /** home.popular-posts-count: 홈 인기글 카드 수 */
  homePopularPostsCount: number;
  /** member.nickname.min-length / max-length (01 BR-MEM-15) */
  nicknameMinLength: number;
  nicknameMaxLength: number;
  /** 마이페이지 미리보기(내가 쓴 글·내 체감 기록) 개수 */
  myPagePreviewCount: number;
};

export const DEFAULT_APP_PARAMS: Readonly<AppParams> = Object.freeze({
  homeStaleAfterMs: 10 * MINUTE,
  locationRefreshIntervalMs: 10 * MINUTE,
  writeLocationMaxAgeMs: 5 * MINUTE,
  permissionPrepromptCooldownMs: 7 * DAY,
  maxInterestNeighborhoods: 5,
  neighborhoodSearchMinLength: 1,
  maxPostImages: 4,
  postMinLength: 1,
  postMaxLength: 1000,
  commentMaxLength: 200,
  imageMaxFileSize: 10 * 1024 * 1024,
  imageMaxDimension: 2048,
  feedPageSize: 20,
  homePopularPostsCount: 5,
  nicknameMinLength: 2,
  nicknameMaxLength: 10,
  myPagePreviewCount: 3,
});

let current: AppParams = { ...DEFAULT_APP_PARAMS };

export function getAppParams(): Readonly<AppParams> {
  return current;
}

/** 원격 설정이 정해지면 받은 값만 덮어쓴다. 알 수 없는 키·잘못된 값은 무시한다. */
export function overrideAppParams(partial: Partial<AppParams>): void {
  const next = { ...current };
  for (const key of Object.keys(DEFAULT_APP_PARAMS) as (keyof AppParams)[]) {
    const value = partial[key];
    if (typeof value === 'number' && Number.isFinite(value) && value >= 0) {
      next[key] = value;
    }
  }
  current = next;
}

export function resetAppParams(): void {
  current = { ...DEFAULT_APP_PARAMS };
}
