// 로그아웃·탈퇴·재발급 실패 때 지우는 것과 남기는 것 (07 §6.1, §6.2 "로그아웃·탈퇴 후", §2-8)
import type { GeneralKey, SecureKey } from './schema';
import { generalStore, secureStore } from './stores';

/** 로그아웃·탈퇴 후 지우는 회원 데이터 */
export const SIGN_OUT_REMOVED = {
  secure: ['authTokens'] as const satisfies readonly SecureKey[],
  general: ['currentNeighborhood', 'lastViewedNeighborhood', 'lastHomeResponse', 'postDraft'] as const satisfies readonly GeneralKey[],
};

/**
 * 로그아웃·탈퇴 후에도 남기는 것. 첫 실행 완료·기기 ID/키는 기기 단위라 회원과 무관하다.
 * 회원의 관심 동네는 원래 서버에 있으므로 비회원 목록으로 복사하지 않는다(공용 기기 보호).
 */
export const SIGN_OUT_KEPT = {
  secure: ['deviceCredential'] as const satisfies readonly SecureKey[],
  general: [
    'onboardingCompleted',
    'guestTemperatureSensitivity',
    'guestInterestNeighborhoods',
    'pendingAction',
    'permissionPrepromptHistory',
    'pushTokenRegistration',
  ] as const satisfies readonly GeneralKey[],
};

/** 로그아웃·탈퇴 직후 기기 정리. 서버 호출 실패와 무관하게 진행한다(SCR-09). */
export async function clearMemberDataOnSignOut(): Promise<void> {
  await Promise.all([
    ...SIGN_OUT_REMOVED.secure.map((key) => secureStore.remove(key)),
    generalStore.remove(...SIGN_OUT_REMOVED.general),
  ]);
}

/** 토큰 재발급 실패: 토큰만 지운다. 작성 초안·보류 행동은 그대로 둔다(07 §2-8). */
export async function clearTokensOnRefreshFailure(): Promise<void> {
  await secureStore.remove('authTokens');
}
