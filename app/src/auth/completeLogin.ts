// 로그인·가입 완료 직후 처리 (07 §3 흐름 LG→MV, §6.2 이전 규칙, §2 규칙 11)
// 토큰 저장 → 기기 확인(기기-회원 연결) → 비회원 관심 동네 이전 → 비회원 민감도 삭제 → (권한 있으면) 현재 동네 판정.
// 토큰 저장 뒤의 단계는 실패해도 로그인 완료를 막지 않는다. 실패한 이전은 기기에 남아 다음 실행 때 다시 시도한다.
import { api } from '@/api';
import type { ApiClient } from '@/api/client';
import { getAppParams } from '@/config/appParams';
import { syncDevice } from '@/device/deviceSync';
import { judgeAndSuggest } from '@/location/judgeCurrentNeighborhood';
import { mergeGuestInterests, type MergeOutcome } from '@/neighborhood/interests';
import { viewingStore } from '@/neighborhood/viewingStore';
import { sessionStore } from '@/session/sessionStore';
import { generalStore } from '@/storage';

import type { MemberSummary, TokenResponse } from './api';
import { termsStatus } from './termsStatus';

export type PostLoginDeps = {
  saveTokens: (tokens: TokenResponse) => Promise<void>;
  syncDevice: () => Promise<unknown>;
  mergeInterests: () => Promise<MergeOutcome>;
  clearGuestSensitivity: () => Promise<void>;
  judgeCurrent: () => Promise<unknown>;
  onMember?: (member: MemberSummary) => void;
};

export type PostLoginResult = {
  /** 최대 개수를 넘어 이전하지 못한 관심 동네 ID — 화면이 이용자에게 알린다(§6.2) */
  discarded: string[];
  /** 이전·기기 확인 등 부가 단계 중 실패한 것(다음 실행 때 다시 맞춘다) */
  failedSteps: string[];
};

export async function completeLogin(
  tokens: TokenResponse,
  member: MemberSummary | null,
  deps: PostLoginDeps = defaultPostLoginDeps(),
): Promise<PostLoginResult> {
  await deps.saveTokens(tokens);
  if (member) {
    deps.onMember?.(member);
  }
  const failedSteps: string[] = [];
  const attempt = async <T>(name: string, run: () => Promise<T>): Promise<T | null> => {
    try {
      return await run();
    } catch {
      failedSteps.push(name);
      return null;
    }
  };

  // 기기-회원 연결이 먼저여야 이후 기기 헤더 요청(홈 외출 시각)이 회원 기준으로 맞는다.
  await attempt('syncDevice', deps.syncDevice);
  const merged = await attempt('mergeInterests', deps.mergeInterests);
  if (merged?.status === 'failed') {
    failedSteps.push('mergeInterests');
  }
  await attempt('clearGuestSensitivity', deps.clearGuestSensitivity);
  await attempt('judgeCurrent', deps.judgeCurrent);

  return { discarded: merged?.status === 'merged' ? merged.discarded : [], failedSteps };
}

export function defaultPostLoginDeps(client: ApiClient = api): PostLoginDeps {
  return {
    saveTokens: (tokens) => sessionStore.saveTokens(tokens),
    syncDevice: () => syncDevice(client),
    mergeInterests: () => mergeGuestInterests(client, getAppParams().maxInterestNeighborhoods),
    // 신규 가입은 민감도 단계의 기본 선택으로만 쓰고, 기존 회원은 버린다 — 어느 쪽이든 완료 후 지운다(§6.1).
    clearGuestSensitivity: () => generalStore.remove('guestTemperatureSensitivity'),
    // 새 좌표로 기기 안에서 다시 판정한다. 보는 동네와 다르면 제안만 띄우고 서버로 보내지 않는다(ADR 0009).
    // 권한이 없으면 판정 함수가 그냥 끝난다(§6.2).
    judgeCurrent: () => judgeAndSuggest(viewingStore),
    onMember: (member) => termsStatus.update(member.termsReagreementRequired, member.termsToReagree),
  };
}

export function discardedNotice(discarded: readonly string[]): string | null {
  if (discarded.length === 0) {
    return null;
  }
  const max = getAppParams().maxInterestNeighborhoods;
  return `관심 동네는 ${max}개까지라 이 기기에 저장한 동네 ${discarded.length}곳은 옮기지 못했어요.`;
}
