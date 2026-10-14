// 회원 전용 쓰기의 단일 관문 (07 §3 가입 유도, §2 규칙 12 재동의 전 쓰기 차단, §5 공통 규칙)
// 비회원 → 보류 행동 저장 + 가입 시트, 재동의 필요 회원 → 보류 행동 저장 + 재동의 시트. 모든 회원 전용 쓰기 버튼이 이것을 거친다.
import { ErrorCodes, isApiError } from '@/api/errors';
import { signupSheet, type SignupSheetMode } from '@/auth/signupSheet';
import { termsStatus } from '@/auth/termsStatus';
import { sessionStore, type SessionStatus } from '@/session/sessionStore';

import { type PendingAction, pendingActionStore } from './pendingAction';

export type WriteGateDeps = {
  sessionStatus: () => SessionStatus;
  reagreementRequired: () => boolean;
  markReagreementRequired: () => void;
  save: (action: PendingAction) => Promise<void>;
  openSheet: (mode: SignupSheetMode) => void;
};

export function createWriteGate(deps: WriteGateDeps) {
  async function block(action: PendingAction, mode: SignupSheetMode): Promise<false> {
    // 저장이 실패해도 시트는 연다 — 화면의 입력은 그대로 남아 있다.
    await deps.save(action).catch(() => undefined);
    deps.openSheet(mode);
    return false;
  }

  return {
    /**
     * 쓰기 직전에 부른다. true면 그대로 진행, false면 시트를 열었으니 화면은 입력을 유지하고 멈춘다.
     */
    async requireMember(action: PendingAction): Promise<boolean> {
      if (deps.sessionStatus() !== 'member') {
        return block(action, 'signup');
      }
      if (deps.reagreementRequired()) {
        return block(action, 'reagree');
      }
      return true;
    },

    /** 보류 행동 없이 가입 시트만 연다(마이페이지 가입 카드, §3.1 마이페이지 행). 회원이면 아무것도 안 한다. */
    openSignup(): void {
      if (deps.sessionStatus() !== 'member') {
        deps.openSheet('signup');
      }
    },

    /**
     * 쓰기 요청이 가입 필요·재동의 필요로 거절됐을 때(토큰 만료로 비회원이 됐거나 앱이 모르던 재동의).
     * @returns 처리했으면 true — 화면은 오류 문구 대신 입력만 유지한다
     */
    async handleWriteError(error: unknown, action: PendingAction): Promise<boolean> {
      if (isApiError(error, ErrorCodes.SIGNUP_REQUIRED)) {
        await block(action, 'signup');
        return true;
      }
      if (isApiError(error, ErrorCodes.TERMS_REAGREEMENT_REQUIRED)) {
        deps.markReagreementRequired();
        await block(action, 'reagree');
        return true;
      }
      return false;
    },
  };
}

export type WriteGate = ReturnType<typeof createWriteGate>;

export const writeGate = createWriteGate({
  sessionStatus: () => sessionStore.getState().status,
  reagreementRequired: () => termsStatus.getState().reagreementRequired,
  markReagreementRequired: () => termsStatus.markRequired(),
  save: (action) => pendingActionStore.save(action),
  openSheet: (mode) => signupSheet.open(mode),
});

/** 07 §3: 모든 회원 전용 행동이 부르는 이름 */
export const requireMember = (action: PendingAction): Promise<boolean> => writeGate.requireMember(action);
