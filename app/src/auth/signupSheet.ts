// SCR-10 가입 시트 열기 (07 §3, §2 규칙 12). 여러 곳(쓰기 버튼·API 오류 신호)에서 동시에 열려 해도 한 번만 띄운다.
import { router } from 'expo-router';

import { routes } from '@/navigation/routes';

export type SignupSheetMode = 'signup' | 'reagree';

let mounted = false;

export const signupSheet = {
  open(mode: SignupSheetMode): void {
    if (mounted) {
      return;
    }
    // 화면이 실제로 마운트되기 전 연속 호출도 막는다.
    mounted = true;
    try {
      router.push(routes.signup(mode === 'reagree' ? { mode: 'reagree' } : {}));
    } catch {
      mounted = false;
    }
  },
  /** 가입 화면이 마운트·언마운트 때 알린다 */
  setMounted(value: boolean): void {
    mounted = value;
  },
  isOpen: (): boolean => mounted,
};
