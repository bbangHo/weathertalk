// 가입 유도·재동의 신호 처리 (07 §2 규칙 10·12, §3, owner-todos §6 오류 코드 분기)
// - API 오류 신호(SIGNUP_REQUIRED·TERMS_REAGREEMENT_REQUIRED) → 가입·재동의 시트
// - 회원이면 회원 상태(재동의 필요 여부)를 member가 명시한 값으로 읽고, 필요하면 홈 진입 때 재동의 시트를 한 번 띄운다
import { useQuery } from '@tanstack/react-query';
import { useEffect } from 'react';

import { api, apiEvents } from '@/api';
import { useViewingState } from '@/neighborhood/viewingStore';
import { queryKeys } from '@/query/queryKeys';
import { sessionStore } from '@/session/sessionStore';
import { useSession } from '@/session/useSession';

import { getMyProfile } from './api';
import { signupSheet } from './signupSheet';
import { termsStatus, useTermsStatus } from './termsStatus';

export function AuthGate() {
  const session = useSession();
  const viewing = useViewingState();
  const terms = useTermsStatus();
  const isMember = session.status === 'member';

  useEffect(() => {
    const offSignup = apiEvents.on('signupRequired', () => signupSheet.open('signup'));
    const offTerms = apiEvents.on('termsReagreementRequired', () => {
      termsStatus.markRequired();
      signupSheet.open('reagree');
    });
    const offSession = sessionStore.subscribe(() => {
      if (sessionStore.getState().status === 'guest') {
        termsStatus.reset();
      }
    });
    return () => {
      offSignup();
      offTerms();
      offSession();
    };
  }, []);

  const profile = useQuery({
    queryKey: queryKeys.me.profile(),
    queryFn: () => getMyProfile(api),
    enabled: isMember,
  });
  useEffect(() => {
    if (profile.data) {
      termsStatus.update(profile.data.termsReagreementRequired, profile.data.termsToReagree);
    }
  }, [profile.data]);

  // 홈 진입은 막지 않고 시트만 한 번(첫 실행 안내 중에는 띄우지 않는다).
  const ready = viewing.hydrated && viewing.onboardingCompleted;
  useEffect(() => {
    if (isMember && ready && terms.reagreementRequired && !terms.promptedThisSession) {
      termsStatus.markPrompted();
      signupSheet.open('reagree');
    }
  }, [isMember, ready, terms.reagreementRequired, terms.promptedThisSession]);

  return null;
}
