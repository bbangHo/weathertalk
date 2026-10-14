// SCR-02 관심 동네: 비회원 기기 저장·최대 개수, 회원 저장 실패 시 되돌림, §6.2 이전
import AsyncStorage from '@react-native-async-storage/async-storage';
import { QueryClient } from '@tanstack/react-query';

import { queryKeys } from '@/query/queryKeys';
import { generalStore } from '@/storage';
import { type FakeCall, jsonResponse, problem, setupClient } from '@/test-utils/apiClient';
import { neighborhoodResponse } from '@/test-utils/homeFixtures';

import { addInterestId, InterestLimitError, moveInterestId, normalizeInterestIds, removeInterestId } from '../interestList';
import { createInterestActions, mergeGuestInterests } from '../interests';
import { toSummary } from '../types';

const MAX = 5;
const n = (id: string) => toSummary(neighborhoodResponse(id, `${id}동`));

function namesHandler(call: FakeCall): Response {
  const url = new URL(call.url);
  if (call.method === 'GET' && url.pathname === '/api/v1/neighborhoods') {
    // 서버는 순서를 보장하지 않는다 — 일부러 뒤집어 돌려준다.
    return jsonResponse(200, url.searchParams.getAll('ids').reverse().map((id) => neighborhoodResponse(id, `${id}동`)));
  }
  return problem(500, 'UNEXPECTED');
}

beforeEach(async () => {
  await AsyncStorage.clear();
});

describe('관심 동네 목록 규칙', () => {
  it('중복은 그대로, 최대 개수를 넘으면 거부', () => {
    expect(addInterestId(['a'], 'a', 2)).toEqual(['a']);
    expect(addInterestId(['a'], 'b', 2)).toEqual(['a', 'b']);
    expect(() => addInterestId(['a', 'b'], 'c', 2)).toThrow(InterestLimitError);
  });
  it('삭제는 멱등, 이동은 범위 안에서만', () => {
    expect(removeInterestId(['a', 'b'], 'x')).toEqual(['a', 'b']);
    expect(moveInterestId(['a', 'b', 'c'], 'c', -1)).toEqual(['a', 'c', 'b']);
    expect(moveInterestId(['a', 'b', 'c'], 'a', -1)).toEqual(['a', 'b', 'c']);
  });
  it('깨진 저장값은 규칙에 맞게 정리', () => {
    expect(normalizeInterestIds(['a', 'a', 3, '', 'b'], 5)).toEqual(['a', 'b']);
    expect(normalizeInterestIds('x', 5)).toEqual([]);
  });
});

describe('비회원 관심 동네 (기기 저장)', () => {
  it('추가·순서 변경·삭제는 기기에만 저장하고 서버에 쓰지 않는다', async () => {
    const { client, calls } = setupClient({ handler: namesHandler });
    const queryClient = new QueryClient();
    const actions = createInterestActions({ api: client, queryClient, scope: 'guest', max: MAX });

    await expect(actions.add(n('A'))).resolves.toEqual({ ok: true });
    await expect(actions.add(n('B'))).resolves.toEqual({ ok: true });
    await expect(actions.move('B', -1)).resolves.toEqual({ ok: true });
    await expect(generalStore.get('guestInterestNeighborhoods')).resolves.toEqual(['B', 'A']);
    await expect(actions.remove('B')).resolves.toEqual({ ok: true });
    await expect(generalStore.get('guestInterestNeighborhoods')).resolves.toEqual(['A']);

    expect(queryClient.getQueryData(queryKeys.neighborhood.interest('guest'))).toEqual([n('A')]);
    expect(calls.filter((call) => call.method !== 'GET')).toEqual([]);
    expect(calls.some((call) => call.url.includes('/members/'))).toBe(false);
  });

  it('최대 개수(회원과 같은 값)에 이르면 더 추가하지 않는다', async () => {
    await generalStore.set('guestInterestNeighborhoods', ['A', 'B', 'C', 'D', 'E']);
    const { client } = setupClient({ handler: namesHandler });
    const actions = createInterestActions({ api: client, queryClient: new QueryClient(), scope: 'guest', max: MAX });

    const result = await actions.add(n('F'));

    expect(result).toMatchObject({ ok: false, reason: 'limit' });
    await expect(generalStore.get('guestInterestNeighborhoods')).resolves.toEqual(['A', 'B', 'C', 'D', 'E']);
  });

  it('이름 조회 결과를 기기 순서대로 맞춘다', async () => {
    await generalStore.set('guestInterestNeighborhoods', ['A', 'B', 'C']);
    const { client } = setupClient({ handler: namesHandler });
    const queryClient = new QueryClient();
    const actions = createInterestActions({ api: client, queryClient, scope: 'guest', max: MAX });

    await actions.add(n('D'));

    expect(
      (queryClient.getQueryData(queryKeys.neighborhood.interest('guest')) as { id: string }[]).map((item) => item.id),
    ).toEqual(['A', 'B', 'C', 'D']);
  });
});

describe('회원 관심 동네 (서버)', () => {
  it('저장에 실패하면 목록을 원래대로 되돌리고 실패를 알린다', async () => {
    const { client } = setupClient({
      handler: (call) =>
        call.method === 'GET'
          ? jsonResponse(200, [neighborhoodResponse('A', 'A동'), neighborhoodResponse('B', 'B동')])
          : problem(500, 'INTERNAL_ERROR'),
    });
    const queryClient = new QueryClient();
    const actions = createInterestActions({ api: client, queryClient, scope: 'member', max: MAX });

    const removal = await actions.remove('A');
    const move = await actions.move('B', -1);

    expect(removal).toMatchObject({ ok: false, reason: 'failed' });
    expect(move).toMatchObject({ ok: false, reason: 'failed' });
    expect(queryClient.getQueryData(queryKeys.neighborhood.interest('member'))).toEqual([n('A'), n('B')]);
  });

  it('서버의 최대 개수 거부도 되돌리고 개수 안내를 준다', async () => {
    const { client } = setupClient({
      handler: (call) =>
        call.method === 'GET' ? jsonResponse(200, []) : problem(422, 'INTEREST_NEIGHBORHOOD_LIMIT_EXCEEDED'),
    });
    const queryClient = new QueryClient();
    const actions = createInterestActions({ api: client, queryClient, scope: 'member', max: MAX });

    await expect(actions.add(n('A'))).resolves.toMatchObject({ ok: false, reason: 'limit' });
    expect(queryClient.getQueryData(queryKeys.neighborhood.interest('member'))).toEqual([]);
  });

  it('성공하면 서버가 돌려준 목록으로 맞춘다', async () => {
    const { client, calls } = setupClient({
      handler: (call) =>
        call.method === 'GET'
          ? jsonResponse(200, [])
          : jsonResponse(200, [neighborhoodResponse('A', 'A동')]),
    });
    const queryClient = new QueryClient();
    const actions = createInterestActions({ api: client, queryClient, scope: 'member', max: MAX });

    await expect(actions.add(n('A'))).resolves.toEqual({ ok: true });
    expect(calls[1]).toMatchObject({ method: 'POST', body: { neighborhoodId: 'A' } });
    expect(queryClient.getQueryData(queryKeys.neighborhood.interest('member'))).toEqual([n('A')]);
  });
});

describe('가입·로그인 시 관심 동네 이전 (§6.2)', () => {
  it('기기 목록을 순서대로 보내고 성공하면 기기 목록을 지운다', async () => {
    await generalStore.set('guestInterestNeighborhoods', ['A', 'B', 'C']);
    const { client, calls } = setupClient({
      handler: () =>
        jsonResponse(200, {
          interests: [neighborhoodResponse('X', 'X동'), neighborhoodResponse('A', 'A동')],
          skipped: ['B'],
          discarded: ['C'],
        }),
    });

    const outcome = await mergeGuestInterests(client, MAX);

    expect(calls[0]).toMatchObject({
      method: 'POST',
      url: 'http://api.test/api/v1/members/me/interest-neighborhoods/merge',
      body: { neighborhoodIds: ['A', 'B', 'C'] },
    });
    expect(outcome).toEqual({ status: 'merged', interests: [n('X'), n('A')], skipped: ['B'], discarded: ['C'] });
    await expect(generalStore.get('guestInterestNeighborhoods')).resolves.toBeNull();
  });

  it('실패하면 기기 목록을 남겨 다음에 다시 시도한다', async () => {
    await generalStore.set('guestInterestNeighborhoods', ['A']);
    const { client } = setupClient({ handler: () => problem(503, 'TEMPORARILY_UNAVAILABLE') });

    const outcome = await mergeGuestInterests(client, MAX);

    expect(outcome.status).toBe('failed');
    await expect(generalStore.get('guestInterestNeighborhoods')).resolves.toEqual(['A']);
  });

  it('기기 목록이 없으면 서버를 부르지 않는다', async () => {
    const { client, calls } = setupClient({ handler: () => jsonResponse(200, {}) });
    await expect(mergeGuestInterests(client, MAX)).resolves.toEqual({ status: 'nothing' });
    expect(calls).toHaveLength(0);
  });
});
