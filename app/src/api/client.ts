// API 클라이언트 (07 §2-7·8, contracts §4, owner-todos §6)
// - 접근 토큰 부착, AUTHENTICATION_FAILED 시 재발급 단일 진행 후 1회 재시도
// - REQUEST_IN_PROGRESS는 같은 Idempotency-Key로 잠시 후 재시도(횟수 제한)
// - 가입 필요·재동의 필요·현재 동네 확인 필요는 이벤트로 알린다
import type { AuthTokens, DeviceCredential } from '@/storage';

import { ApiError, ErrorCodes, NetworkError, parseErrorBody } from './errors';
import { type ApiEvents, dispatchApiError } from './events';
import { IDEMPOTENCY_HEADER, requiresIdempotencyKey } from './idempotency';

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';

type QueryValue = string | number | boolean | null | undefined;

export type RequestOptions = {
  method?: HttpMethod;
  query?: Record<string, QueryValue | readonly QueryValue[]>;
  body?: unknown;
  /** 접근 토큰을 붙일지. 기본 true(있을 때만 붙는다). */
  auth?: boolean;
  /** 생성성 쓰기 요청 식별자. 같은 이용자 행동의 재시도에는 같은 값을 넘긴다. */
  idempotencyKey?: string;
  /** X-Device-Id·X-Device-Key를 붙일지(홈·기기 설정 요청). 기기 등록 전이면 생략된다. */
  device?: boolean;
  signal?: AbortSignal;
};

/** 세션 저장소 중 클라이언트가 쓰는 부분 */
export type TokenAccess = {
  getTokens(): AuthTokens | null;
  saveTokens(tokens: AuthTokens): Promise<void>;
  expire(): Promise<void>;
};

export type ApiClientDeps = {
  baseUrl: string;
  session: TokenAccess;
  getDeviceCredential: () => Promise<DeviceCredential | null>;
  events: ApiEvents;
  fetchFn?: typeof fetch;
  sleep?: (ms: number) => Promise<void>;
  /** REQUEST_IN_PROGRESS 재시도: 최대 횟수와 기본 대기(ms, 회차마다 늘린다) */
  inProgressRetry?: { maxRetries: number; baseDelayMs: number };
};

export const DEVICE_ID_HEADER = 'X-Device-Id';
export const DEVICE_KEY_HEADER = 'X-Device-Key';
export const REFRESH_PATH = '/api/v1/auth/refresh';

/** 백엔드 LoginResponse.TokenResponse */
export type TokenResponse = {
  accessToken: string;
  accessTokenExpiresAt: string;
  refreshToken: string;
  refreshTokenExpiresAt: string;
};

const defaultSleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

function buildUrl(baseUrl: string, path: string, query: RequestOptions['query']): string {
  const params: string[] = [];
  for (const [key, raw] of Object.entries(query ?? {})) {
    const values = Array.isArray(raw) ? raw : [raw];
    for (const value of values) {
      if (value !== null && value !== undefined) {
        params.push(`${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`);
      }
    }
  }
  return baseUrl + path + (params.length ? `?${params.join('&')}` : '');
}

export function createApiClient(deps: ApiClientDeps) {
  const fetchFn = deps.fetchFn ?? ((input, init) => fetch(input, init));
  const sleep = deps.sleep ?? defaultSleep;
  const inProgressRetry = deps.inProgressRetry ?? { maxRetries: 3, baseDelayMs: 700 };
  let refreshInFlight: Promise<boolean> | null = null;

  async function send<T>(path: string, options: RequestOptions, accessToken: string | null): Promise<T> {
    const method = options.method ?? 'GET';
    if (requiresIdempotencyKey(method, path) && !options.idempotencyKey) {
      throw new Error(`${method} ${path} 요청에는 idempotencyKey가 필요합니다.`);
    }
    const headers: Record<string, string> = { Accept: 'application/json' };
    if (options.body !== undefined) {
      headers['Content-Type'] = 'application/json';
    }
    if (accessToken) {
      headers.Authorization = `Bearer ${accessToken}`;
    }
    if (options.idempotencyKey) {
      headers[IDEMPOTENCY_HEADER] = options.idempotencyKey;
    }
    if (options.device) {
      const device = await deps.getDeviceCredential();
      if (device) {
        headers[DEVICE_ID_HEADER] = device.deviceId;
        headers[DEVICE_KEY_HEADER] = device.deviceKey;
      }
    }

    let response: Response;
    try {
      response = await fetchFn(buildUrl(deps.baseUrl, path, options.query), {
        method,
        headers,
        body: options.body === undefined ? undefined : JSON.stringify(options.body),
        signal: options.signal,
      });
    } catch (cause) {
      throw new NetworkError(cause);
    }

    const text = await response.text().catch(() => '');
    if (!response.ok) {
      throw parseErrorBody(response.status, text);
    }
    if (response.status === 204 || text === '') {
      return undefined as T;
    }
    return JSON.parse(text) as T;
  }

  /** 재발급 성공 여부. 4xx로 거절되면 세션 만료, 네트워크·5xx는 토큰을 지우지 않고 던진다. */
  async function performRefresh(refreshToken: string): Promise<boolean> {
    try {
      const tokens = await send<TokenResponse>(
        REFRESH_PATH,
        { method: 'POST', body: { refreshToken } },
        null,
      );
      await deps.session.saveTokens({
        accessToken: tokens.accessToken,
        accessTokenExpiresAt: tokens.accessTokenExpiresAt,
        refreshToken: tokens.refreshToken,
        refreshTokenExpiresAt: tokens.refreshTokenExpiresAt,
      });
      return true;
    } catch (error) {
      if (error instanceof ApiError && error.status >= 400 && error.status < 500) {
        await deps.session.expire();
        deps.events.emit('sessionExpired', error);
        return false;
      }
      throw error;
    }
  }

  /** 재발급 단일 진행: 동시에 실패한 요청은 같은 재발급을 기다린다(07 §2-7). */
  function refreshOnce(usedAccessToken: string): Promise<boolean> {
    const current = deps.session.getTokens();
    if (!current) {
      return Promise.resolve(false);
    }
    // 이미 다른 요청이 재발급을 끝냈다면 새 토큰으로 재시도만 한다.
    if (current.accessToken !== usedAccessToken) {
      return Promise.resolve(true);
    }
    if (!refreshInFlight) {
      refreshInFlight = performRefresh(current.refreshToken).finally(() => {
        refreshInFlight = null;
      });
    }
    return refreshInFlight;
  }

  async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
    const useAuth = options.auth ?? true;
    let refreshed = false;
    let inProgressRetries = 0;

    for (;;) {
      const accessToken = useAuth ? (deps.session.getTokens()?.accessToken ?? null) : null;
      try {
        return await send<T>(path, options, accessToken);
      } catch (error) {
        if (!(error instanceof ApiError)) {
          throw error;
        }
        if (error.code === ErrorCodes.AUTHENTICATION_FAILED && accessToken && !refreshed) {
          refreshed = true;
          if (await refreshOnce(accessToken)) {
            continue;
          }
          throw error;
        }
        if (
          error.code === ErrorCodes.REQUEST_IN_PROGRESS &&
          options.idempotencyKey &&
          inProgressRetries < inProgressRetry.maxRetries
        ) {
          inProgressRetries += 1;
          await sleep(inProgressRetry.baseDelayMs * inProgressRetries);
          continue;
        }
        dispatchApiError(deps.events, error);
        throw error;
      }
    }
  }

  return {
    request,
    get: <T>(path: string, options: Omit<RequestOptions, 'method' | 'body'> = {}) =>
      request<T>(path, { ...options, method: 'GET' }),
    post: <T>(path: string, body?: unknown, options: Omit<RequestOptions, 'method' | 'body'> = {}) =>
      request<T>(path, { ...options, method: 'POST', body }),
    put: <T>(path: string, body?: unknown, options: Omit<RequestOptions, 'method' | 'body'> = {}) =>
      request<T>(path, { ...options, method: 'PUT', body }),
    patch: <T>(path: string, body?: unknown, options: Omit<RequestOptions, 'method' | 'body'> = {}) =>
      request<T>(path, { ...options, method: 'PATCH', body }),
    delete: <T>(path: string, options: Omit<RequestOptions, 'method' | 'body'> = {}) =>
      request<T>(path, { ...options, method: 'DELETE' }),
  };
}

export type ApiClient = ReturnType<typeof createApiClient>;
