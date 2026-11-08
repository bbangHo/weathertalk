import { createApiClient } from '@/api/client';
import { createApiEvents } from '@/api/events';
import type { PostDetail, PostPage } from '@/community/types';
import type { HomeView } from '@/home/types';

import { createDemoServer, DEMO_CURRENT_ID } from '../demoServer';

function demoApi() {
  let tokens = {
    accessToken: 'demo-access',
    accessTokenExpiresAt: '',
    refreshToken: 'demo-refresh',
    refreshTokenExpiresAt: '',
  };
  return createApiClient({
    baseUrl: 'https://example.invalid',
    session: { getTokens: () => tokens, saveTokens: async (next) => void (tokens = next), expire: async () => {} },
    getDeviceCredential: async () => null,
    events: createApiEvents(),
    fetchFn: createDemoServer().fetch,
  });
}

describe('데모 가짜 서버', () => {
  it('홈은 모든 섹션이 채워지고 체감은 동네 체감 기준이다', async () => {
    const home = await demoApi().get<HomeView>('/api/v1/home');
    expect(home.neighborhood).toMatchObject({ id: DEMO_CURRENT_ID, eupmyeondongName: '역삼1동', current: true });
    for (const key of [
      'currentWeather',
      'hourlyForecast',
      'dailyForecast',
      'sensitivityFeelings',
      'outfit',
      'yesterdayComparison',
      'popularPosts',
      'divergence',
      'fieldReports',
    ] as const) {
      expect(home[key]?.status).toBe('OK');
    }
    expect(home.sensitivityFeelings.data!.groups.map((g) => g.source)).toEqual(Array(3).fill('NEIGHBORHOOD_FEELING'));
    expect(home.divergence.data!.axes.some((axis) => axis.badge)).toBe(true);
  });

  it('피드는 페이지로 나뉘고, 쓰기는 메모리에만 반영된다', async () => {
    const api = demoApi();
    const path = `/api/v1/community/neighborhoods/${DEMO_CURRENT_ID}/posts`;
    const first = await api.get<PostPage>(path, { query: { size: 10 } });
    expect(first.items).toHaveLength(10);
    expect(new Set(first.items.map((p) => p.author.nickname)).size).toBeGreaterThan(5);
    const second = await api.get<PostPage>(path, { query: { size: 10, cursor: first.nextCursor } });
    expect(second.nextCursor).toBeNull();

    const target = first.items[0];
    await api.put(`/api/v1/community/posts/${target.postId}/like`, { liked: true });
    await api.post(
      `/api/v1/community/posts/${target.postId}/comments`,
      { body: '데모 댓글' },
      { idempotencyKey: 'k1' },
    );
    const detail = await api.get<PostDetail>(`/api/v1/community/posts/${target.postId}`);
    expect(detail.post).toMatchObject({ likedByViewer: true, likeCount: target.likeCount + 1 });
    expect(detail.comments.items.at(-1)).toMatchObject({ body: '데모 댓글', mine: true });

    const { postId } = await api.post<{ postId: string }>(
      '/api/v1/community/posts',
      { body: '새 글', imageIds: [], feelingRecordId: null, latitude: 0, longitude: 0, accuracyMeters: null },
      { idempotencyKey: 'k2' },
    );
    expect((await api.get<PostPage>(path)).items[0].postId).toBe(postId);
  });

  it('모르는 요청은 오류로 답한다', async () => {
    await expect(demoApi().get('/api/v1/unknown')).rejects.toMatchObject({ code: 'DEMO_NOT_SUPPORTED' });
  });
});
