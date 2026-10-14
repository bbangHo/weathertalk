import type { AuthTokens, DeviceCredential } from '@/storage';

import { createApiClient, type TokenAccess } from '@/api/client';
import { createApiEvents } from '@/api/events';

export type FakeCall = { url: string; method: string; headers: Record<string, string>; body: unknown };

export function jsonResponse(status: number, body?: unknown): Response {
  const text = body === undefined ? '' : JSON.stringify(body);
  return { ok: status >= 200 && status < 300, status, text: async () => text } as unknown as Response;
}

export function problem(status: number, code: string, extra: Record<string, unknown> = {}): Response {
  return jsonResponse(status, { type: 'about:blank', title: 'x', status, detail: `${code} 안내`, code, ...extra });
}

export function tokens(suffix: string): AuthTokens {
  return {
    accessToken: `access-${suffix}`,
    accessTokenExpiresAt: '2026-10-15T00:00:00Z',
    refreshToken: `refresh-${suffix}`,
    refreshTokenExpiresAt: '2026-11-15T00:00:00Z',
  };
}

/** 메모리 세션: 실제 sessionStore와 같은 계약(TokenAccess) */
export function memorySession(initial: AuthTokens | null) {
  let current = initial;
  const session = {
    expired: 0,
    saved: [] as AuthTokens[],
    getTokens: () => current,
    saveTokens: jest.fn(async (next: AuthTokens) => {
      current = next;
      session.saved.push(next);
    }),
    expire: jest.fn(async () => {
      current = null;
      session.expired += 1;
    }),
  };
  return session satisfies TokenAccess;
}

export function setupClient(options: {
  handler: (call: FakeCall) => Response | Promise<Response>;
  session?: ReturnType<typeof memorySession>;
  device?: DeviceCredential | null;
}) {
  const calls: FakeCall[] = [];
  const events = createApiEvents();
  const session = options.session ?? memorySession(null);
  const sleep = jest.fn(async (_ms: number) => {});
  const fetchFn = jest.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const call: FakeCall = {
      url: String(input),
      method: init?.method ?? 'GET',
      headers: { ...(init?.headers as Record<string, string>) },
      body: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined,
    };
    calls.push(call);
    return options.handler(call);
  });
  const client = createApiClient({
    baseUrl: 'http://api.test',
    session,
    getDeviceCredential: async () => options.device ?? null,
    events,
    fetchFn: fetchFn as unknown as typeof fetch,
    sleep,
  });
  return { client, calls, events, session, sleep, fetchFn };
}
