// 앱 설정 파라미터 기본값 (07 §1.6). 원격 전달 방식은 미결(07 §9-6)이라 여기서만 모아 둔다.

const MINUTE = 60 * 1000;
const DAY = 24 * 60 * MINUTE;

export type AppParams = {
  /** app.home-stale-after: 포그라운드 복귀 시 홈 재조회 기준 경과 시간(ms) */
  homeStaleAfterMs: number;
  /** app.location-refresh-interval: 현재 동네 재판정 최소 간격(ms) */
  locationRefreshIntervalMs: number;
  /** app.write-location-max-age: 쓰기 직전 현재 동네 판정의 최대 경과 시간(ms) */
  writeLocationMaxAgeMs: number;
  /** app.permission-preprompt-cooldown: 권한 사전 안내 거절 후 재노출 간격(ms) */
  permissionPrepromptCooldownMs: number;
};

export const DEFAULT_APP_PARAMS: Readonly<AppParams> = Object.freeze({
  homeStaleAfterMs: 10 * MINUTE,
  locationRefreshIntervalMs: 10 * MINUTE,
  writeLocationMaxAgeMs: 5 * MINUTE,
  permissionPrepromptCooldownMs: 7 * DAY,
});

let current: AppParams = { ...DEFAULT_APP_PARAMS };

export function getAppParams(): Readonly<AppParams> {
  return current;
}

/** 원격 설정이 정해지면 받은 값만 덮어쓴다. 알 수 없는 키·잘못된 값은 무시한다. */
export function overrideAppParams(partial: Partial<AppParams>): void {
  const next = { ...current };
  for (const key of Object.keys(DEFAULT_APP_PARAMS) as (keyof AppParams)[]) {
    const value = partial[key];
    if (typeof value === 'number' && Number.isFinite(value) && value >= 0) {
      next[key] = value;
    }
  }
  current = next;
}

export function resetAppParams(): void {
  current = { ...DEFAULT_APP_PARAMS };
}
