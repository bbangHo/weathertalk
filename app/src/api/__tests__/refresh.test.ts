// 07 §2-7·8: 재발급 단일 진행, 실패 시 비회원 전환
import { jsonResponse, memorySession, problem, setupClient, tokens } from '@/test-utils/apiClient';

import { ErrorCodes } from '../errors';

const REFRESH = '/api/v1/auth/refresh';

function isRefresh(url: string) {
  return url.endsWith(REFRESH);
}

describe('토큰 재발급', () => {
  it('동시에 N개 요청이 만료로 실패하면 재발급은 한 번만 하고 모두 새 토큰으로 재시도한다', async () => {
    const session = memorySession(tokens('old'));
    let releaseRefresh: () => void = () => {};
    const refreshGate = new Promise<void>((resolve) => {
      releaseRefresh = resolve;
    });

    const { client, calls } = setupClient({
      session,
      handler: async (call) => {
        if (isRefresh(call.url)) {
          await refreshGate;
          return jsonResponse(200, tokens('new'));
        }
        if (call.headers.Authorization === 'Bearer access-old') {
          return problem(401, ErrorCodes.AUTHENTICATION_FAILED);
        }
        return jsonResponse(200, { url: call.url });
      },
    });

    const N = 5;
    const pending = Array.from({ length: N }, (_, i) => client.get<{ url: string }>(`/api/v1/r${i}`));
    // 모든 요청이 첫 응답(401)을 받고 재발급을 기다리게 한 뒤 재발급을 끝낸다.
    await new Promise((resolve) => setTimeout(resolve, 0));
    releaseRefresh();
    const results = await Promise.all(pending);

    expect(calls.filter((call) => isRefresh(call.url))).toHaveLength(1);
    expect(results.map((r) => r.url)).toEqual(Array.from({ length: N }, (_, i) => `http://api.test/api/v1/r${i}`));
    const retried = calls.filter((call) => !isRefresh(call.url) && call.headers.Authorization === 'Bearer access-new');
    expect(retried).toHaveLength(N);
    // 새 접근·재발급 토큰을 함께 저장
    expect(session.saved).toEqual([tokens('new')]);
    expect(calls.find((call) => isRefresh(call.url))?.body).toEqual({ refreshToken: 'refresh-old' });
  });

  it('재발급 뒤에 늦게 도착한 만료 응답은 다시 재발급하지 않고 새 토큰으로 재시도한다', async () => {
    const session = memorySession(tokens('old'));
    const { client, calls } = setupClient({
      session,
      handler: (call) => {
        if (isRefresh(call.url)) {
          return jsonResponse(200, tokens('new'));
        }
        return call.headers.Authorization === 'Bearer access-new'
          ? jsonResponse(200, {})
          : problem(401, ErrorCodes.AUTHENTICATION_FAILED);
      },
    });

    await client.get('/api/v1/a');
    // 이미 새 토큰이 저장된 상태에서 예전 토큰으로 보낸 요청을 흉내 낸다.
    await session.saveTokens(tokens('new'));
    await client.get('/api/v1/b');

    expect(calls.filter((call) => isRefresh(call.url))).toHaveLength(1);
  });

  it('재발급이 거절되면 토큰 삭제·비회원 전환·배너 신호 후 원래 오류를 던지고, 동시 요청도 재발급은 한 번', async () => {
    const session = memorySession(tokens('old'));
    const { client, calls, events } = setupClient({
      session,
      handler: (call) =>
        isRefresh(call.url)
          ? problem(401, ErrorCodes.AUTHENTICATION_FAILED)
          : problem(401, ErrorCodes.AUTHENTICATION_FAILED),
    });
    const expired = jest.fn();
    events.on('sessionExpired', expired);

    const results = await Promise.allSettled([client.get('/api/v1/a'), client.get('/api/v1/b')]);

    expect(results.every((r) => r.status === 'rejected')).toBe(true);
    expect(results[0]).toMatchObject({ reason: { code: ErrorCodes.AUTHENTICATION_FAILED } });
    expect(calls.filter((call) => isRefresh(call.url))).toHaveLength(1);
    expect(session.expire).toHaveBeenCalledTimes(1);
    expect(session.getTokens()).toBeNull();
    expect(expired).toHaveBeenCalledTimes(1);
  });

  it('재발급 중 네트워크 오류면 토큰을 지우지 않는다', async () => {
    const session = memorySession(tokens('old'));
    const { client } = setupClient({
      session,
      handler: (call) => {
        if (isRefresh(call.url)) {
          throw new TypeError('Network request failed');
        }
        return problem(401, ErrorCodes.AUTHENTICATION_FAILED);
      },
    });

    await expect(client.get('/api/v1/a')).rejects.toMatchObject({ name: 'NetworkError' });
    expect(session.expire).not.toHaveBeenCalled();
    expect(session.getTokens()).toEqual(tokens('old'));
  });

  it('토큰 없이 보낸 요청(비회원)은 재발급하지 않는다', async () => {
    const { client, calls } = setupClient({ handler: () => problem(401, ErrorCodes.SIGNUP_REQUIRED) });
    await expect(client.get('/api/v1/members/me')).rejects.toMatchObject({ code: ErrorCodes.SIGNUP_REQUIRED });
    expect(calls).toHaveLength(1);
    expect(calls[0].headers.Authorization).toBeUndefined();
  });

  it('재시도한 요청이 또 만료면 반복하지 않고 던진다', async () => {
    const session = memorySession(tokens('old'));
    const { client, calls } = setupClient({
      session,
      handler: (call) => (isRefresh(call.url) ? jsonResponse(200, tokens('new')) : problem(401, ErrorCodes.AUTHENTICATION_FAILED)),
    });
    await expect(client.get('/api/v1/a')).rejects.toMatchObject({ code: ErrorCodes.AUTHENTICATION_FAILED });
    expect(calls.map((call) => call.url.replace('http://api.test', ''))).toEqual(['/api/v1/a', REFRESH, '/api/v1/a']);
  });
});
