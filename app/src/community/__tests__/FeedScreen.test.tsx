// SCR-06 피드: 페이지 이어 불러오기, 빈 상태, 글쓰기 버튼(현재 동네만), 좋아요(낙관적·실패 되돌림·본인 글·비회원 보류·복귀 자동 적용)
import AsyncStorage from '@react-native-async-storage/async-storage';
import type { InfiniteData } from '@tanstack/react-query';
import { act, fireEvent, screen, waitFor } from '@testing-library/react-native';
import * as SecureStore from 'expo-secure-store';

import { signupSheet } from '@/auth/signupSheet';
import { termsStatus } from '@/auth/termsStatus';
import { viewingStore } from '@/neighborhood/viewingStore';
import { pendingActionStore, restorePendingAction, restoredActions } from '@/pending/pendingAction';
import { setActiveQueryClient } from '@/query/activeClient';
import { queryKeys } from '@/query/queryKeys';
import { sessionStore } from '@/session/sessionStore';
import { generalStore } from '@/storage';
import { jsonResponse, problem, tokens, type FakeCall } from '@/test-utils/apiClient';
import { page, post } from '@/test-utils/communityFixtures';
import { installFetch, pathOf } from '@/test-utils/globalFetch';
import { neighborhoodResponse } from '@/test-utils/homeFixtures';
import { renderWithClient, testQueryClient } from '@/test-utils/render';
import { mockRouter, resetRouterMock } from '@/test-utils/routerMock';

import { FEED_EMPTY, FeedScreen, WRITE_ONLY_CURRENT } from '../FeedScreen';
import { SELF_LIKE_NOTICE } from '../likes';
import type { PostPage } from '../types';

// 팩토리 안에서는 import를 쓸 수 없다(호이스팅).
// eslint-disable-next-line @typescript-eslint/no-require-imports
jest.mock('expo-router', () => require('@/test-utils/routerMock').expoRouterMock());

async function setViewing(viewingId: string, currentId: string | null) {
  await generalStore.set('onboardingCompleted', true);
  await generalStore.set('lastViewedNeighborhood', { neighborhoodId: viewingId });
  if (currentId) {
    await generalStore.set('currentNeighborhood', { neighborhoodId: currentId, resolvedAt: new Date().toISOString() });
  }
  await viewingStore.hydrate();
}

type Handler = (call: FakeCall) => Response | Promise<Response>;

function server(feed: Handler, extra: Handler = () => problem(500, 'X')) {
  return installFetch((call) => {
    const path = pathOf(call);
    if (path.startsWith('/api/v1/neighborhoods/')) {
      return jsonResponse(200, neighborhoodResponse(path.split('/').pop()!, '역삼1동'));
    }
    if (path.startsWith('/api/v1/community/neighborhoods/')) {
      return feed(call);
    }
    return extra(call);
  });
}

const likeCalls = (calls: FakeCall[]) => calls.filter((call) => call.method === 'PUT' && pathOf(call).endsWith('/like'));

beforeEach(async () => {
  await AsyncStorage.clear();
  (SecureStore as unknown as { __store: Map<string, string> }).__store.clear();
  sessionStore.becomeGuest();
  termsStatus.reset();
  restoredActions.reset();
  signupSheet.setMounted(false);
  viewingStore.reset();
  resetRouterMock();
});

describe('피드 목록', () => {
  it('첫 페이지를 보여주고 끝에 닿으면 커서로 다음 페이지를 이어 붙인다', async () => {
    await setViewing('N1', 'N1');
    const { calls } = server((call) => {
      const cursor = new URL(call.url).searchParams.get('cursor');
      return jsonResponse(200, cursor ? page([post('p3')]) : page([post('p1'), post('p2')], 'c1'));
    });
    renderWithClient(<FeedScreen />);

    expect(await screen.findByText('본문 p1')).toBeTruthy();
    expect(screen.getByText('본문 p2')).toBeTruthy();
    const first = calls.find((call) => pathOf(call) === '/api/v1/community/neighborhoods/N1/posts')!;
    expect(new URL(first.url).searchParams.get('size')).toBe('20');

    fireEvent(screen.getByTestId('feed-list'), 'onEndReached');
    expect(await screen.findByText('본문 p3')).toBeTruthy();
    const second = calls.filter((call) => pathOf(call) === '/api/v1/community/neighborhoods/N1/posts')[1];
    expect(new URL(second.url).searchParams.get('cursor')).toBe('c1');
    // 마지막 페이지(커서 없음) 뒤에는 더 부르지 않는다
    fireEvent(screen.getByTestId('feed-list'), 'onEndReached');
    expect(calls.filter((call) => pathOf(call) === '/api/v1/community/neighborhoods/N1/posts')).toHaveLength(2);
  });

  it('현재 동네의 글이 없으면 빈 상태와 첫 글 쓰기를 보여준다', async () => {
    await setViewing('N1', 'N1');
    server(() => jsonResponse(200, page([])));
    renderWithClient(<FeedScreen />);

    expect(await screen.findByText(FEED_EMPTY)).toBeTruthy();
    fireEvent.press(screen.getByTestId('feed-first-post'));
    expect(mockRouter.push).toHaveBeenCalledWith('/post-editor');
  });

  it('다른 동네를 볼 때는 글쓰기를 막고 안내하며, 빈 상태에도 첫 글 쓰기가 없다', async () => {
    await setViewing('N2', 'N1');
    server(() => jsonResponse(200, page([])));
    renderWithClient(<FeedScreen />);

    expect(await screen.findByText(FEED_EMPTY)).toBeTruthy();
    expect(screen.getByTestId('feed-write').props.accessibilityState.disabled).toBe(true);
    expect(screen.getByText(WRITE_ONLY_CURRENT)).toBeTruthy();
    expect(screen.queryByTestId('feed-first-post')).toBeNull();
  });

  it('현재 동네면 글쓰기가 켜지고 작성 모달을 연다', async () => {
    await setViewing('N1', 'N1');
    server(() => jsonResponse(200, page([post('p1')])));
    renderWithClient(<FeedScreen />);
    await screen.findByText('본문 p1');

    expect(screen.getByTestId('feed-write').props.accessibilityState.disabled).toBe(false);
    expect(screen.queryByText(WRITE_ONLY_CURRENT)).toBeNull();
    fireEvent.press(screen.getByTestId('feed-write'));
    expect(mockRouter.push).toHaveBeenCalledWith('/post-editor');
  });

  it('목록 조회가 실패하면 영역 오류와 다시 시도를 보여준다', async () => {
    await setViewing('N1', 'N1');
    let fail = true;
    server(() => (fail ? problem(500, 'X') : jsonResponse(200, page([post('p1')]))));
    renderWithClient(<FeedScreen />);

    expect(await screen.findByText('글을 불러오지 못했어요')).toBeTruthy();
    fail = false;
    fireEvent.press(screen.getByText('다시 시도'));
    expect(await screen.findByText('본문 p1')).toBeTruthy();
  });
});

describe('좋아요', () => {
  it('누르면 바로 표시를 바꾸고 최종 상태를 보낸 뒤 서버 값으로 맞춘다', async () => {
    await sessionStore.saveTokens(tokens('m'));
    await setViewing('N2', 'N1');
    let release: (response: Response) => void = () => undefined;
    const { calls } = server(
      () => jsonResponse(200, page([post('p1', { likeCount: 2 })])),
      (call) =>
        pathOf(call).endsWith('/like') ? new Promise<Response>((resolve) => (release = resolve)) : problem(500, 'X'),
    );
    renderWithClient(<FeedScreen />);
    await screen.findByText('본문 p1');

    fireEvent.press(screen.getByTestId('like-p1'));
    await waitFor(() => expect(screen.getByTestId('like-count-p1').props.children).toBe('3'));
    expect(likeCalls(calls)[0].body).toEqual({ liked: true });
    expect(screen.getByTestId('like-p1').props.accessibilityState.disabled).toBe(true);

    await act(async () => release(jsonResponse(200, { liked: true, likeCount: 7 })));
    await waitFor(() => expect(screen.getByTestId('like-count-p1').props.children).toBe('7'));
    expect(screen.getByTestId('like-p1').props.accessibilityState.selected).toBe(true);
  });

  it('실패하면 표시를 되돌리고 알린다', async () => {
    await sessionStore.saveTokens(tokens('m'));
    await setViewing('N1', 'N1');
    server(
      () => jsonResponse(200, page([post('p1', { likeCount: 2, likedByViewer: true })])),
      (call) => (pathOf(call).endsWith('/like') ? problem(503, 'TEMPORARILY_UNAVAILABLE') : problem(500, 'X')),
    );
    renderWithClient(<FeedScreen />);
    await screen.findByText('본문 p1');

    fireEvent.press(screen.getByTestId('like-p1'));
    expect(await screen.findByTestId('feed-notice')).toBeTruthy();
    expect(screen.getByTestId('like-count-p1').props.children).toBe('2');
    expect(screen.getByTestId('like-p1').props.accessibilityState.selected).toBe(true);
  });

  it('서버가 본인 글이라 거부하면 되돌리고 본인 글 안내를 보여준다', async () => {
    await sessionStore.saveTokens(tokens('m'));
    await setViewing('N1', 'N1');
    server(
      () => jsonResponse(200, page([post('p1', { likeCount: 0 })])),
      () => problem(422, 'SELF_LIKE_NOT_ALLOWED'),
    );
    renderWithClient(<FeedScreen />);
    await screen.findByText('본문 p1');

    fireEvent.press(screen.getByTestId('like-p1'));
    expect(await screen.findByText(SELF_LIKE_NOTICE)).toBeTruthy();
    expect(screen.getByTestId('like-count-p1').props.children).toBe('0');
  });

  it('본인 글은 요청을 보내지 않고 안내만 한다', async () => {
    await sessionStore.saveTokens(tokens('m'));
    await setViewing('N1', 'N1');
    const { calls } = server(() => jsonResponse(200, page([post('p1', { mine: true })])));
    renderWithClient(<FeedScreen />);
    await screen.findByText('본문 p1');

    fireEvent.press(screen.getByTestId('like-p1'));
    expect(await screen.findByText(SELF_LIKE_NOTICE)).toBeTruthy();
    expect(likeCalls(calls)).toHaveLength(0);
  });

  it('비회원은 좋아요를 보류 행동으로 남기고 가입 시트를 열며 서버로 보내지 않는다', async () => {
    await setViewing('N2', null);
    const { calls } = server(() => jsonResponse(200, page([post('p1', { likedByViewer: null, mine: null })])));
    renderWithClient(<FeedScreen />);
    await screen.findByText('본문 p1');

    fireEvent.press(screen.getByTestId('like-p1'));
    await waitFor(async () =>
      expect(await pendingActionStore.load()).toEqual({
        kind: 'like',
        route: '/community',
        neighborhoodId: null,
        payload: { postId: 'p1', liked: true },
      }),
    );
    expect(mockRouter.push).toHaveBeenCalledWith('/signup');
    expect(likeCalls(calls)).toHaveLength(0);
    expect(screen.getByTestId('like-count-p1').props.children).toBe('2');
  });

  it('가입 뒤 복원하면 등록된 처리기가 좋아요를 자동 적용하고 캐시를 서버 값으로 바꾼다', async () => {
    await sessionStore.saveTokens(tokens('m'));
    const { calls } = installFetch((call) =>
      pathOf(call) === '/api/v1/community/posts/p1/like'
        ? jsonResponse(200, { liked: true, likeCount: 3 })
        : jsonResponse(200, page([post('p1')])),
    );
    const client = testQueryClient();
    client.setQueryData<InfiniteData<PostPage>>(queryKeys.community.feed('N1'), {
      pages: [page([post('p1', { likeCount: 2 })])],
      pageParams: [null],
    });
    setActiveQueryClient(client);
    await pendingActionStore.save({ kind: 'like', route: '/community', neighborhoodId: null, payload: { postId: 'p1', liked: true } });

    const delivered = jest.fn();
    const outcome = await restorePendingAction({
      load: () => pendingActionStore.load(),
      clear: () => pendingActionStore.clear(),
      checkCurrentNeighborhood: async () => 'ok',
      navigate: () => undefined,
      deliver: delivered,
    });

    expect(outcome).toMatchObject({ status: 'restored', autoApplied: true, notice: null });
    expect(likeCalls(calls)[0].body).toEqual({ liked: true });
    expect(calls.find((call) => pathOf(call).endsWith('/like'))!.headers.Authorization).toBe('Bearer access-m');
    const cached = client.getQueryData<InfiniteData<PostPage>>(queryKeys.community.feed('N1'));
    expect(cached?.pages[0].items[0]).toMatchObject({ likedByViewer: true, likeCount: 3 });
    setActiveQueryClient(null);
  });
});
