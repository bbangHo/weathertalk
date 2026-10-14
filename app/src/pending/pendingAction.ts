// 보류 행동 (07 §1.7, §3.1 트리거 표, §3 규칙 4~7, §6.1 보류 행동)
// - 하나만 보관하고 새 가입 유도 때 덮어쓴다. 가입 취소·복원 완료 때 지운다.
// - 복귀 시 좋아요만 자동 적용하고 나머지는 원래 화면과 입력을 복원만 한다(자동 제출 안 함).
// - 현재 동네가 필요한 행동(체감 기록·피드백·현장 제보·게시글)은 판정이 오래됐으면 재판정하고, 다르거나 판정 불가면 안내를 붙인다.
import { useEffect, useRef } from 'react';

import type { FeedbackAnswer, HumidityTagName, TemperatureTagName, WindTagName } from '@/feeling/types';
import { generalStore, type StoredPendingAction, type TemperatureSensitivity } from '@/storage';

/** 행동별 입력. A3~A5가 쓰는 종류는 그 단계에서 필드를 더한다. */
export type PendingPayloads = {
  feelingRecord: {
    temperature: TemperatureTagName | null;
    humidity: HumidityTagName | null;
    wind: WindTagName | null;
  };
  /** 누른 답과 당시 보여준 그룹·태그(§3.1) */
  feedback: { answer: FeedbackAnswer; group: TemperatureSensitivity; temperatureTag: TemperatureTagName };
  fieldReport: { selections: Record<string, string> };
  /** 본문·이미지·첨부 기록은 작성 초안(postDraft)에 있다 */
  post: { feelingRecordId: string | null };
  comment: { postId: string; parentCommentId: string | null; body: string };
  /** 좋아요 최종 상태 */
  like: { postId: string; liked: boolean };
};

export type PendingKind = keyof PendingPayloads;

export type PendingActionOf<K extends PendingKind> = {
  kind: K;
  /** 복귀할 화면 경로 */
  route: string;
  /** 행동 당시 보던 동네. 현재 동네여야 하는 행동은 복원 때 현재 동네와 비교한다(체감 기록은 null — 보는 동네와 무관). */
  neighborhoodId: string | null;
  payload: PendingPayloads[K];
};

export type PendingAction = { [K in PendingKind]: PendingActionOf<K> }[PendingKind];

const KINDS: readonly PendingKind[] = ['feelingRecord', 'feedback', 'fieldReport', 'post', 'comment', 'like'];

/** §3 규칙 7: 댓글·답글·좋아요는 다른 동네 글에도 할 수 있어 위치 재판정 없이 복원한다. */
export function needsCurrentNeighborhood(kind: PendingKind): boolean {
  return kind === 'feelingRecord' || kind === 'feedback' || kind === 'fieldReport' || kind === 'post';
}

export const NOT_CURRENT_NOTICE = '현재 동네에서만 남길 수 있어요';
export const LIKE_FAILED_NOTICE = '좋아요를 반영하지 못했어요. 다시 눌러 주세요.';

export const pendingActionStore = {
  async save(action: PendingAction, now: Date = new Date()): Promise<void> {
    const stored: StoredPendingAction = { ...action, savedAt: now.toISOString() };
    await generalStore.set('pendingAction', stored);
  },
  async load(): Promise<PendingAction | null> {
    const stored = await generalStore.get('pendingAction');
    if (!stored || !KINDS.includes(stored.kind as PendingKind) || typeof stored.route !== 'string') {
      return null;
    }
    return {
      kind: stored.kind,
      route: stored.route,
      neighborhoodId: stored.neighborhoodId ?? null,
      payload: stored.payload,
    } as PendingAction;
  },
  async clear(): Promise<void> {
    await generalStore.remove('pendingAction');
  },
};

// ── 복원 결과 전달: 원래 화면이 구독해 입력을 채운다 ──

export type RestoredAction = {
  action: PendingAction;
  /** 화면에 함께 보여줄 안내(현재 동네 불일치·좋아요 실패 등) */
  notice: string | null;
  /** 좋아요처럼 복귀 때 이미 적용했는가 */
  autoApplied: boolean;
};

function createRestoredSlot() {
  let current: RestoredAction | null = null;
  const listeners = new Set<() => void>();
  return {
    put(restored: RestoredAction): void {
      current = restored;
      listeners.forEach((listener) => listener());
    },
    /** 종류가 맞으면 꺼내고 비운다(한 화면이 한 번만 복원한다). */
    take<K extends PendingKind>(kind: K): (RestoredAction & { action: PendingActionOf<K> }) | null {
      if (!current || current.action.kind !== kind) {
        return null;
      }
      const taken = current as RestoredAction & { action: PendingActionOf<K> };
      current = null;
      return taken;
    },
    peek: (): RestoredAction | null => current,
    subscribe(listener: () => void): () => void {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    reset(): void {
      current = null;
    },
  };
}

export const restoredActions = createRestoredSlot();

/** 화면에서: 마운트 때와 복원이 도착할 때 그 종류의 복원 내용을 받는다. */
export function useRestoredAction<K extends PendingKind>(
  kind: K,
  onRestore: (restored: RestoredAction & { action: PendingActionOf<K> }) => void,
): void {
  const handler = useRef(onRestore);
  useEffect(() => {
    handler.current = onRestore;
  });
  useEffect(() => {
    const check = () => {
      const taken = restoredActions.take(kind);
      if (taken) {
        handler.current(taken);
      }
    };
    check();
    return restoredActions.subscribe(check);
  }, [kind]);
}

// ── 자동 적용(좋아요) 연결점: A3 community가 실제 좋아요 호출을 등록한다 ──

type AutoApplyKind = 'like';
type AutoApplyHandler<K extends AutoApplyKind> = (action: PendingActionOf<K>) => Promise<void>;
const autoApplyHandlers: { like?: AutoApplyHandler<'like'> } = {};

export function registerAutoApply(kind: 'like', handler: AutoApplyHandler<'like'>): () => void {
  autoApplyHandlers[kind] = handler;
  return () => {
    if (autoApplyHandlers[kind] === handler) {
      delete autoApplyHandlers[kind];
    }
  };
}

export type LocationCheck = 'ok' | 'mismatch' | 'unavailable';

export type RestoreDeps = {
  load: () => Promise<PendingAction | null>;
  clear: () => Promise<void>;
  /** 현재 동네가 필요한 행동만 부른다 */
  checkCurrentNeighborhood: (action: PendingAction) => Promise<LocationCheck>;
  /** 원래 화면으로 돌아간다 */
  navigate: (action: PendingAction) => void;
  deliver?: (restored: RestoredAction) => void;
};

export type RestoreOutcome = { status: 'none' } | ({ status: 'restored' } & RestoredAction);

/** 로그인·가입·재동의 완료 뒤, 또는 앱 재시작 때 회원이면 보류 행동을 복원한다. */
export async function restorePendingAction(deps: RestoreDeps): Promise<RestoreOutcome> {
  const action = await deps.load();
  if (!action) {
    return { status: 'none' };
  }
  await deps.clear().catch(() => undefined);
  const deliver = deps.deliver ?? restoredActions.put;

  let notice: string | null = null;
  let autoApplied = false;
  if (action.kind === 'like') {
    const handler = autoApplyHandlers.like;
    if (handler) {
      try {
        await handler(action);
        autoApplied = true;
      } catch {
        notice = LIKE_FAILED_NOTICE;
      }
    }
  } else if (needsCurrentNeighborhood(action.kind)) {
    const check = await deps.checkCurrentNeighborhood(action).catch((): LocationCheck => 'unavailable');
    notice = check === 'ok' ? null : NOT_CURRENT_NOTICE;
  }
  const restored: RestoredAction = { action, notice, autoApplied };
  deliver(restored);
  deps.navigate(action);
  return { status: 'restored', ...restored };
}

/** 현재 동네 비교: 보던 동네가 기록된 행동은 같은 동네여야 하고, 체감 기록은 판정만 되면 된다. */
export function compareWithCurrent(action: PendingAction, currentId: string | null): LocationCheck {
  if (currentId === null) {
    return 'unavailable';
  }
  return action.neighborhoodId === null || action.neighborhoodId === currentId ? 'ok' : 'mismatch';
}
