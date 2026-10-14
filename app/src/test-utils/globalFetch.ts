// 앱 전역 api 인스턴스(@/api)를 쓰는 화면 테스트용: 전역 fetch를 가짜로 바꾼다.
import type { FakeCall } from './apiClient';

export function installFetch(handler: (call: FakeCall) => Response | Promise<Response>) {
  const calls: FakeCall[] = [];
  const fetchMock = jest.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const call: FakeCall = {
      url: String(input),
      method: init?.method ?? 'GET',
      headers: { ...(init?.headers as Record<string, string>) },
      body: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined,
    };
    calls.push(call);
    return handler(call);
  });
  (globalThis as { fetch: unknown }).fetch = fetchMock;
  return { calls, fetchMock };
}

export function pathOf(call: FakeCall): string {
  return new URL(call.url).pathname;
}
