// SCR-07 상세: 댓글·답글 1단계(답글엔 답글 버튼 없음), 삭제된 댓글 자리, 비회원 댓글 입력 보존, 답글 작성·요청 식별자,
// 실패 시 입력 유지·같은 식별자로 다시 시도, 삭제된 글 처리, 본인 글·댓글 삭제 확인
import AsyncStorage from '@react-native-async-storage/async-storage';
import { act, fireEvent, screen, waitFor } from '@testing-library/react-native';
import * as SecureStore from 'expo-secure-store';
import { Alert } from 'react-native';

import { signupSheet } from '@/auth/signupSheet';
import { termsStatus } from '@/auth/termsStatus';
import { pendingActionStore, restoredActions } from '@/pending/pendingAction';
import { sessionStore } from '@/session/sessionStore';
import { jsonResponse, problem, tokens, type FakeCall } from '@/test-utils/apiClient';
import { comment, detail, post } from '@/test-utils/communityFixtures';
import { installFetch, pathOf } from '@/test-utils/globalFetch';
import { renderWithClient } from '@/test-utils/render';
import { mockRouter, resetRouterMock } from '@/test-utils/routerMock';

import { DELETED_COMMENT, POST_DELETED, PostDetailScreen } from '../PostDetailScreen';

// 팩토리 안에서는 import를 쓸 수 없다(호이스팅).
// eslint-disable-next-line @typescript-eslint/no-require-imports
jest.mock('expo-router', () => require('@/test-utils/routerMock').expoRouterMock());

type AlertButton = { text?: string; onPress?: () => void | Promise<void> };

/** 확인 대화상자에서 그 버튼을 누른다 */
async function pressAlert(spy: jest.SpyInstance, text: string) {
  const buttons = spy.mock.calls[spy.mock.calls.length - 1][2] as AlertButton[];
  await act(async () => {
    await buttons.find((button) => button.text === text)!.onPress!();
  });
}

const THREAD = [
  comment('c1', { replies: [comment('r1'), comment('r2', { mine: true })] }),
  comment('c2', { deleted: true, body: null, author: null, replies: [comment('r3')] }),
  comment('c3', { mine: true }),
];

const commentPosts = (calls: FakeCall[]) =>
  calls.filter((call) => call.method === 'POST' && /\/(comments|replies)$/.test(pathOf(call)));

let alertSpy: jest.SpyInstance;

beforeEach(async () => {
  await AsyncStorage.clear();
  (SecureStore as unknown as { __store: Map<string, string> }).__store.clear();
  sessionStore.becomeGuest();
  termsStatus.reset();
  restoredActions.reset();
  signupSheet.setMounted(false);
  resetRouterMock();
  alertSpy = jest.spyOn(Alert, 'alert').mockImplementation(() => undefined);
});

afterEach(() => alertSpy.mockRestore());

it('본문·댓글·답글을 보여주고, 답글에는 답글 버튼이 없으며 지운 댓글은 자리만 남는다', async () => {
  installFetch(() => jsonResponse(200, detail(post('p1', { commentCount: 5 }), THREAD)));
  renderWithClient(<PostDetailScreen postId="p1" />);

  expect(await screen.findByTestId('post-body')).toBeTruthy();
  expect(screen.getByText('댓글 c1')).toBeTruthy();
  expect(screen.getByText('댓글 r1')).toBeTruthy();
  expect(screen.getByTestId('reply-c1')).toBeTruthy();
  expect(screen.queryByTestId('reply-r1')).toBeNull();
  expect(screen.queryByTestId('reply-r2')).toBeNull();
  // 지운 댓글: 문구만, 작성자·본문·답글 버튼 없음. 그 아래 답글은 그대로
  expect(screen.getByText(DELETED_COMMENT)).toBeTruthy();
  expect(screen.queryByTestId('reply-c2')).toBeNull();
  expect(screen.getByText('댓글 r3')).toBeTruthy();
  // 본인 댓글·답글에만 삭제
  expect(screen.getByTestId('delete-comment-c3')).toBeTruthy();
  expect(screen.getByTestId('delete-comment-r2')).toBeTruthy();
  expect(screen.queryByTestId('delete-comment-c1')).toBeNull();
  // 남의 글이면 수정·삭제 메뉴가 없다
  expect(screen.queryByTestId('post-edit')).toBeNull();
});

it('댓글이 없으면 첫 댓글을 권한다', async () => {
  installFetch(() => jsonResponse(200, detail(post('p1'), [])));
  renderWithClient(<PostDetailScreen postId="p1" />);
  expect(await screen.findByText('첫 댓글을 남겨 보세요')).toBeTruthy();
});

it('비회원이 댓글을 남기면 입력을 보류 행동으로 저장하고 가입 시트를 열며, 입력창은 그대로 둔다', async () => {
  const { calls } = installFetch(() => jsonResponse(200, detail(post('p1'), THREAD)));
  renderWithClient(<PostDetailScreen postId="p1" />);
  await screen.findByTestId('post-body');

  fireEvent.press(screen.getByTestId('reply-c1'));
  fireEvent.changeText(screen.getByTestId('comment-input'), '저도요');
  fireEvent.press(screen.getByTestId('comment-submit'));

  await waitFor(async () =>
    expect(await pendingActionStore.load()).toEqual({
      kind: 'comment',
      route: '/community/p1',
      neighborhoodId: null,
      payload: { postId: 'p1', parentCommentId: 'c1', body: '저도요' },
    }),
  );
  expect(mockRouter.push).toHaveBeenCalledWith('/signup');
  expect(commentPosts(calls)).toHaveLength(0);
  expect(screen.getByTestId('comment-input').props.value).toBe('저도요');
});

it('가입 뒤 복원된 댓글 입력을 채운다(자동 제출 안 함)', async () => {
  const { calls } = installFetch(() => jsonResponse(200, detail(post('p1'), THREAD)));
  restoredActions.put({
    action: { kind: 'comment', route: '/community/p1', neighborhoodId: null, payload: { postId: 'p1', parentCommentId: 'c1', body: '복원' } },
    notice: null,
    autoApplied: false,
  });
  renderWithClient(<PostDetailScreen postId="p1" />);
  await screen.findByTestId('post-body');

  await waitFor(() => expect(screen.getByTestId('comment-input').props.value).toBe('복원'));
  expect(screen.getByTestId('reply-target')).toBeTruthy();
  expect(commentPosts(calls)).toHaveLength(0);
});

it('회원은 다른 동네 글에도 위치 없이 답글을 단다 — 요청 식별자를 붙이고 성공하면 입력을 비우고 다시 불러온다', async () => {
  await sessionStore.saveTokens(tokens('m'));
  let detailCalls = 0;
  const { calls } = installFetch((call) => {
    if (pathOf(call) === '/api/v1/community/comments/c1/replies') {
      return jsonResponse(200, { commentId: 'r9' });
    }
    detailCalls += 1;
    return jsonResponse(200, detail(post('p1', { neighborhood: { id: 'N9', name: '먼동', shortName: '먼동' } }), THREAD));
  });
  renderWithClient(<PostDetailScreen postId="p1" />);
  await screen.findByTestId('post-body');

  fireEvent.press(screen.getByTestId('reply-c1'));
  fireEvent.changeText(screen.getByTestId('comment-input'), '  답글  ');
  fireEvent.press(screen.getByTestId('comment-submit'));

  await waitFor(() => expect(commentPosts(calls)).toHaveLength(1));
  const sent = commentPosts(calls)[0];
  expect(sent.body).toEqual({ body: '답글' });
  expect(sent.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
  expect(sent.body).not.toHaveProperty('latitude');
  await waitFor(() => expect(screen.getByTestId('comment-input').props.value).toBe(''));
  await waitFor(() => expect(detailCalls).toBeGreaterThan(1));
});

it('댓글 작성이 실패하면 입력을 유지하고, 다시 시도는 같은 요청 식별자로 보낸다', async () => {
  await sessionStore.saveTokens(tokens('m'));
  let fail = true;
  const { calls } = installFetch((call) => {
    if (pathOf(call) === '/api/v1/community/posts/p1/comments') {
      return fail ? problem(503, 'TEMPORARILY_UNAVAILABLE') : jsonResponse(200, { commentId: 'c9' });
    }
    return jsonResponse(200, detail(post('p1'), []));
  });
  renderWithClient(<PostDetailScreen postId="p1" />);
  await screen.findByTestId('post-body');

  fireEvent.changeText(screen.getByTestId('comment-input'), '첫 댓글');
  fireEvent.press(screen.getByTestId('comment-submit'));
  expect(await screen.findByTestId('comment-error')).toBeTruthy();
  expect(screen.getByTestId('comment-input').props.value).toBe('첫 댓글');
  expect(screen.getByText('다시 시도')).toBeTruthy();

  fail = false;
  fireEvent.press(screen.getByTestId('comment-submit'));
  await waitFor(() => expect(commentPosts(calls)).toHaveLength(2));
  const [first, second] = commentPosts(calls);
  expect(second.headers['Idempotency-Key']).toBe(first.headers['Idempotency-Key']);
  await waitFor(() => expect(screen.getByTestId('comment-input').props.value).toBe(''));
});

it('없는(삭제된) 글이면 "삭제된 글이에요"를 보여주고 이전 화면으로 돌아간다', async () => {
  installFetch(() => problem(404, 'NOT_FOUND'));
  renderWithClient(<PostDetailScreen postId="gone" />);

  expect(await screen.findByText(POST_DELETED)).toBeTruthy();
  await waitFor(() => expect(mockRouter.back).toHaveBeenCalled(), { timeout: 3000 });
});

it('본인 글은 수정으로 가고, 삭제는 확인 후 지우고 돌아간다', async () => {
  await sessionStore.saveTokens(tokens('m'));
  const { calls } = installFetch((call) =>
    call.method === 'DELETE' ? jsonResponse(204) : jsonResponse(200, detail(post('p1', { mine: true }), [])),
  );
  renderWithClient(<PostDetailScreen postId="p1" />);
  await screen.findByTestId('post-body');

  fireEvent.press(screen.getByTestId('post-edit'));
  expect(mockRouter.push).toHaveBeenCalledWith({ pathname: '/post-editor', params: { postId: 'p1' } });

  fireEvent.press(screen.getByTestId('post-delete'));
  expect(calls.filter((call) => call.method === 'DELETE')).toHaveLength(0);
  await pressAlert(alertSpy, '삭제');
  expect(calls.filter((call) => call.method === 'DELETE').map(pathOf)).toEqual(['/api/v1/community/posts/p1']);
  expect(mockRouter.back).toHaveBeenCalled();
});

it('본인 댓글은 확인 후 지우고 목록을 다시 불러온다', async () => {
  await sessionStore.saveTokens(tokens('m'));
  const { calls } = installFetch((call) =>
    call.method === 'DELETE' ? jsonResponse(204) : jsonResponse(200, detail(post('p1'), THREAD)),
  );
  renderWithClient(<PostDetailScreen postId="p1" />);
  await screen.findByTestId('post-body');

  fireEvent.press(screen.getByTestId('delete-comment-c3'));
  await pressAlert(alertSpy, '삭제');
  expect(calls.filter((call) => call.method === 'DELETE').map(pathOf)).toEqual(['/api/v1/community/comments/c3']);
  await waitFor(() => expect(calls.filter((call) => pathOf(call) === '/api/v1/community/posts/p1').length).toBe(2));
});
