// 쿼리 키 (07 §5 "데이터 변경 후 갱신"). 상위 키(all)로 무효화하면 하위 키가 함께 무효화된다.
// 예: 게시글 작성 성공 → invalidateQueries({ queryKey: queryKeys.community.feeds() }) + queryKeys.home.all

export const queryKeys = {
  home: {
    all: ['home'] as const,
    byNeighborhood: (neighborhoodId: string | null) => ['home', neighborhoodId] as const,
  },
  neighborhood: {
    all: ['neighborhood'] as const,
    search: (keyword: string) => ['neighborhood', 'search', keyword] as const,
    /** 관심 동네. 회원(서버)과 비회원(기기)을 섞지 않게 범위를 키에 넣는다. */
    interest: (scope?: 'member' | 'guest') =>
      scope ? (['neighborhood', 'interest', scope] as const) : (['neighborhood', 'interest'] as const),
    detail: (neighborhoodId: string) => ['neighborhood', 'detail', neighborhoodId] as const,
  },
  community: {
    all: ['community'] as const,
    feeds: () => ['community', 'feed'] as const,
    feed: (neighborhoodId: string) => ['community', 'feed', neighborhoodId] as const,
    posts: () => ['community', 'post'] as const,
    post: (postId: string) => ['community', 'post', postId] as const,
    comments: (postId: string) => ['community', 'post', postId, 'comments'] as const,
  },
  feeling: {
    all: ['feeling'] as const,
    tags: () => ['feeling', 'tags'] as const,
  },
  weather: {
    all: ['weather'] as const,
    fieldReports: (neighborhoodId: string) => ['weather', 'field-reports', neighborhoodId] as const,
  },
  me: {
    all: ['me'] as const,
    profile: () => ['me', 'profile'] as const,
    posts: () => ['me', 'posts'] as const,
    feelingRecords: () => ['me', 'feeling-records'] as const,
    calibrationOffer: () => ['me', 'calibration-offer'] as const,
  },
  notification: {
    all: ['notification'] as const,
    subscriptions: () => ['notification', 'subscriptions'] as const,
    departureTimes: () => ['notification', 'departure-times'] as const,
  },
  terms: {
    all: ['terms'] as const,
  },
} as const;
