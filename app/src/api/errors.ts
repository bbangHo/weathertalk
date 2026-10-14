// 백엔드 오류 응답(RFC 9457 Problem Detail + code 속성) 해석 (contracts §4, shared/error/ApiExceptionHandler)

/** 앱이 분기하는 공통 오류 코드 (owner-todos §6). 그 밖의 모듈 오류 코드도 문자열로 그대로 받는다. */
export const ErrorCodes = {
  SIGNUP_REQUIRED: 'SIGNUP_REQUIRED',
  AUTHENTICATION_FAILED: 'AUTHENTICATION_FAILED',
  CURRENT_NEIGHBORHOOD_UNVERIFIED: 'CURRENT_NEIGHBORHOOD_UNVERIFIED',
  TERMS_REAGREEMENT_REQUIRED: 'TERMS_REAGREEMENT_REQUIRED',
  REQUEST_IN_PROGRESS: 'REQUEST_IN_PROGRESS',
  INVALID_REQUEST: 'INVALID_REQUEST',
  NOT_FOUND: 'NOT_FOUND',
  TEMPORARILY_UNAVAILABLE: 'TEMPORARILY_UNAVAILABLE',
  VIEWING_NEIGHBORHOOD_UNRESOLVED: 'VIEWING_NEIGHBORHOOD_UNRESOLVED',
  DEVICE_KEY_MISMATCH: 'DEVICE_KEY_MISMATCH',
} as const;

export type KnownErrorCode = (typeof ErrorCodes)[keyof typeof ErrorCodes];

/** 응답 본문에 code가 없을 때(프록시 오류 페이지 등) 붙이는 코드 */
export const UNKNOWN_ERROR_CODE = 'UNKNOWN';

const PROBLEM_FIELDS = new Set(['type', 'title', 'status', 'detail', 'instance', 'code']);

/** 서버가 오류로 응답한 경우 */
export class ApiError extends Error {
  readonly code: string;
  readonly status: number;
  /** 서버가 준 안내 문구(detail). 화면에 그대로 보여줄 수 있다. */
  readonly detail: string | null;
  /** code 외 추가 속성(reason, nextChangeAvailableAt 등) */
  readonly properties: Readonly<Record<string, unknown>>;

  constructor(params: { code: string; status: number; detail: string | null; properties?: Record<string, unknown> }) {
    super(params.detail ?? `${params.code} (${params.status})`);
    this.name = 'ApiError';
    this.code = params.code;
    this.status = params.status;
    this.detail = params.detail;
    this.properties = Object.freeze({ ...(params.properties ?? {}) });
  }

  /** 거부 사유(reason) 속성. 없으면 null. */
  get reason(): string | null {
    const reason = this.properties.reason;
    return typeof reason === 'string' ? reason : null;
  }

  is(code: KnownErrorCode): boolean {
    return this.code === code;
  }
}

/** 응답을 받지 못한 경우(오프라인, 시간 초과, 주소 오류 등) */
export class NetworkError extends Error {
  readonly cause: unknown;

  constructor(cause: unknown) {
    super('네트워크에 연결하지 못했어요.');
    this.name = 'NetworkError';
    this.cause = cause;
  }
}

export function isApiError(error: unknown, code?: KnownErrorCode): error is ApiError {
  return error instanceof ApiError && (code === undefined || error.code === code);
}

export function isNetworkError(error: unknown): error is NetworkError {
  return error instanceof NetworkError;
}

/** 오류 응답 본문 텍스트를 ApiError로 바꾼다. 형식이 달라도 상태 코드는 보존한다. */
export function parseErrorBody(status: number, bodyText: string): ApiError {
  let body: unknown = null;
  if (bodyText) {
    try {
      body = JSON.parse(bodyText);
    } catch {
      body = null;
    }
  }
  if (body === null || typeof body !== 'object' || Array.isArray(body)) {
    return new ApiError({ code: UNKNOWN_ERROR_CODE, status, detail: null });
  }
  const record = body as Record<string, unknown>;
  const properties: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(record)) {
    if (!PROBLEM_FIELDS.has(key)) {
      properties[key] = value;
    }
  }
  return new ApiError({
    code: typeof record.code === 'string' ? record.code : UNKNOWN_ERROR_CODE,
    status: typeof record.status === 'number' ? record.status : status,
    detail: typeof record.detail === 'string' ? record.detail : null,
    properties,
  });
}
