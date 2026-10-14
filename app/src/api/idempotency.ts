// 생성성 쓰기의 요청 식별자 (contracts §4, backend shared/idempotency/IdempotencyKey)
import * as Crypto from 'expo-crypto';
import { useCallback, useRef } from 'react';

export const IDEMPOTENCY_HEADER = 'Idempotency-Key';

/**
 * 서버가 Idempotency-Key를 요구하는 생성성 쓰기 (백엔드 컨트롤러 기준).
 * 체감 기록·체감 피드백·현장 제보·게시글·댓글·답글. 좋아요는 최종 상태를 보내므로 필요 없다.
 */
const IDEMPOTENT_POST_PATHS: readonly RegExp[] = [
  /^\/api\/v1\/feeling\/records$/,
  /^\/api\/v1\/feeling\/feedbacks$/,
  /^\/api\/v1\/weather\/field-reports$/,
  /^\/api\/v1\/community\/posts$/,
  /^\/api\/v1\/community\/posts\/[^/]+\/comments$/,
  /^\/api\/v1\/community\/comments\/[^/]+\/replies$/,
];

export function requiresIdempotencyKey(method: string, path: string): boolean {
  return method === 'POST' && IDEMPOTENT_POST_PATHS.some((pattern) => pattern.test(path));
}

/** 새 요청 식별자(UUID v4). 서버 형식: 영문·숫자·'-'·'_' 8~100자. */
export function newIdempotencyKey(): string {
  return Crypto.randomUUID();
}

/**
 * 한 번의 이용자 행동에 식별자 하나. "다시 시도"는 같은 식별자를 다시 쓰고, 성공했거나 입력이 바뀌면 renew한다.
 * 같은 식별자를 써야 서버가 중복 제출을 한 번으로 처리한다.
 */
export function createIdempotencyKeyHolder() {
  let key = newIdempotencyKey();
  return {
    current: (): string => key,
    renew: (): string => {
      key = newIdempotencyKey();
      return key;
    },
  };
}

/** 화면에서 쓰는 형태. 컴포넌트가 살아 있는 동안 같은 행동의 재시도에 같은 식별자를 준다. */
export function useIdempotencyKey(): { current: () => string; renew: () => string } {
  const holder = useRef<ReturnType<typeof createIdempotencyKeyHolder> | null>(null);
  if (holder.current === null) {
    holder.current = createIdempotencyKeyHolder();
  }
  const current = useCallback(() => holder.current!.current(), []);
  const renew = useCallback(() => holder.current!.renew(), []);
  return { current, renew };
}
