// 기기 로컬 저장 항목 (07 §6.1). 이 표에 없는 키는 저장하지 않는다 — 새 키는 07 문서에 먼저 추가한다.

export type TemperatureSensitivity = 'HEAT_SENSITIVE' | 'NORMAL' | 'COLD_SENSITIVE';

/** 인증 토큰. 접근·재발급 토큰을 한 항목으로 저장해 항상 함께 바뀐다(07 §2-7). */
export type AuthTokens = {
  accessToken: string;
  accessTokenExpiresAt: string;
  refreshToken: string;
  refreshTokenExpiresAt: string;
};

/** notification `기기 확인`이 발급한 기기 ID·기기 키. */
export type DeviceCredential = {
  deviceId: string;
  deviceKey: string;
};

/** OS 보안 저장소(키체인/키스토어) 항목 */
export type SecureSchema = {
  authTokens: AuthTokens;
  deviceCredential: DeviceCredential;
};

/** 현재 동네: 마지막 판정 동네 ID와 판정 시각. 좌표는 넣지 않는다(ADR 0009). */
export type StoredCurrentNeighborhood = {
  neighborhoodId: string;
  resolvedAt: string;
};

export type PermissionPrepromptHistory = {
  /** 위치 사전 안내를 거절한 시각(ISO) */
  locationDeclinedAt?: string;
  /** 알림 사전 안내를 거절한 시각(ISO) */
  notificationDeclinedAt?: string;
};

/** 보류 행동 (07 §3). 내용 형식은 행동별로 A1~A5가 정한다. */
export type StoredPendingAction = {
  kind: string;
  savedAt: string;
  payload: unknown;
};

/** 작성 초안 (SCR-08): 본문·이미지 참조·첨부 체감 기록 */
export type StoredPostDraft = {
  body: string;
  imageIds: string[];
  feelingRecordId: string | null;
  savedAt: string;
};

/** 일반 저장소 항목 */
export type GeneralSchema = {
  onboardingCompleted: boolean;
  guestTemperatureSensitivity: TemperatureSensitivity;
  guestInterestNeighborhoods: string[];
  currentNeighborhood: StoredCurrentNeighborhood;
  lastViewedNeighborhood: { neighborhoodId: string };
  lastHomeResponse: { neighborhoodId: string; savedAt: string; body: unknown };
  pendingAction: StoredPendingAction;
  postDraft: StoredPostDraft;
  permissionPrepromptHistory: PermissionPrepromptHistory;
  pushTokenRegistration: { pushToken: string };
};

export type SecureKey = keyof SecureSchema;
export type GeneralKey = keyof GeneralSchema;

// 런타임 검사용 목록. 타입과 어긋나면 satisfies에서 컴파일 오류가 난다.
export const SECURE_KEYS = ['authTokens', 'deviceCredential'] as const satisfies readonly SecureKey[];

export const GENERAL_KEYS = [
  'onboardingCompleted',
  'guestTemperatureSensitivity',
  'guestInterestNeighborhoods',
  'currentNeighborhood',
  'lastViewedNeighborhood',
  'lastHomeResponse',
  'pendingAction',
  'postDraft',
  'permissionPrepromptHistory',
  'pushTokenRegistration',
] as const satisfies readonly GeneralKey[];

// 목록에 빠진 키가 있으면 여기서 컴파일 오류가 난다.
type AssertNever<T extends never> = T;
export type _AllGeneralKeysListed = AssertNever<Exclude<GeneralKey, (typeof GENERAL_KEYS)[number]>>;
export type _AllSecureKeysListed = AssertNever<Exclude<SecureKey, (typeof SECURE_KEYS)[number]>>;

// 실제 저장 키는 앱 접두어를 붙인다. 보안 저장소 키 규칙(영숫자 . - _)을 지킨다.
export const STORAGE_KEY_PREFIX = 'wt.';
