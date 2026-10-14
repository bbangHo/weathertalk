// SCR-01 11번 동네 인기글: 받은 글만(가짜 카드 없음) 최대 home.popular-posts-count장, 0건 빈 상태 + 글쓰기(현재 동네만), 더 보기 → 피드
import { fireEvent, screen } from '@testing-library/react-native';

import { post } from '@/test-utils/communityFixtures';
import { homeView, ok } from '@/test-utils/homeFixtures';
import { renderWithClient } from '@/test-utils/render';
import { mockRouter, resetRouterMock } from '@/test-utils/routerMock';

import { PopularPostsSection } from '../components/PopularPostsSection';

// 팩토리 안에서는 import를 쓸 수 없다(호이스팅).
// eslint-disable-next-line @typescript-eslint/no-require-imports
jest.mock('expo-router', () => require('@/test-utils/routerMock').expoRouterMock());

beforeEach(() => resetRouterMock());

it('받은 인기글을 최대 5장까지 카드로 보여주고, 카드는 상세로·더 보기는 피드로 간다', () => {
  const posts = ['a', 'b', 'c', 'd', 'e', 'f'].map((id) => post(id));
  renderWithClient(<PopularPostsSection home={homeView({ popularPosts: ok(posts) })} viewingKind="interest" onRetry={jest.fn()} />);

  expect(screen.getAllByTestId(/^post-card-/)).toHaveLength(5);
  expect(screen.queryByTestId('post-card-f')).toBeNull();
  fireEvent.press(screen.getByTestId('open-post-a'));
  expect(mockRouter.push).toHaveBeenCalledWith('/community/a');
  fireEvent.press(screen.getByTestId('popular-more'));
  expect(mockRouter.navigate).toHaveBeenCalledWith('/community');
});

it('0건이면 빈 상태와 글쓰기(현재 동네일 때만)를 보여준다', () => {
  const empty = homeView({ popularPosts: { status: 'EMPTY', data: [], failure: null } });
  const { rerender } = renderWithClient(<PopularPostsSection home={empty} viewingKind="current" onRetry={jest.fn()} />);
  expect(screen.getByText('아직 이 동네 글이 없어요')).toBeTruthy();
  fireEvent.press(screen.getByTestId('popular-write'));
  expect(mockRouter.push).toHaveBeenCalledWith('/post-editor');

  rerender(<PopularPostsSection home={empty} viewingKind="searched" onRetry={jest.fn()} />);
  expect(screen.getByText('아직 이 동네 글이 없어요')).toBeTruthy();
  expect(screen.queryByTestId('popular-write')).toBeNull();
});

it('섹션 실패면 불러오지 못했어요 + 다시 시도', () => {
  const onRetry = jest.fn();
  renderWithClient(
    <PopularPostsSection
      home={homeView({ popularPosts: { status: 'FAILED', data: null, failure: 'TIMEOUT' } })}
      viewingKind="current"
      onRetry={onRetry}
    />,
  );
  fireEvent.press(screen.getByText('다시 시도'));
  expect(onRetry).toHaveBeenCalled();
});
