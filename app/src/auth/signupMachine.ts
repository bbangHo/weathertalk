// SCR-10 가입 단계 (07 §3 흐름·규칙 1·2, SCR-10, 01 FR-MEM-02·03·12). 부수효과 없는 상태 전이만 둔다.
// 신규: 제공자 인증 → 필수 약관 → 온도 민감도 → 완료(무작위 닉네임 안내)
// 기존 회원: 제공자 인증 → (재동의 필요 시 재동의) → 복귀
import type { AgreementInput, MemberSummary, TermsResponse } from './api';

export type SignupState =
  | { step: 'provider'; error: string | null }
  | { step: 'terms'; ticket: string; terms: TermsResponse[] }
  | { step: 'sensitivity'; ticket: string; terms: TermsResponse[]; agreements: AgreementInput[] }
  | { step: 'reagree'; terms: TermsResponse[]; discarded: string[] }
  | { step: 'done'; nickname: string; discarded: string[] }
  | { step: 'notice'; discarded: string[] }
  | { step: 'finished'; reagreed: boolean | null };

export type SignupEvent =
  /** 기존 회원 로그인 완료(토큰 저장·로컬 데이터 이전까지 끝남) */
  | { type: 'LOGGED_IN'; member: MemberSummary; discarded: string[] }
  /** 처음 보는 계정: 가입 티켓과 약관 목록 */
  | { type: 'SIGNUP_NEEDED'; ticket: string; terms: TermsResponse[] }
  | { type: 'TERMS_CONFIRMED'; agreements: AgreementInput[] }
  | { type: 'BACK' }
  | { type: 'SIGNED_UP'; nickname: string; discarded: string[] }
  /** 가입 티켓 만료·사용됨 → 로그인부터 다시 */
  | { type: 'TICKET_INVALID'; message: string }
  | { type: 'REAGREED' }
  /** 재동의 "나중에" → 조회만 가능한 상태로 닫는다 */
  | { type: 'LATER' }
  | { type: 'ACK' };

export function initialSignupState(mode: 'signup' | 'reagree', termsToReagree: TermsResponse[] = []): SignupState {
  return mode === 'reagree' ? { step: 'reagree', terms: termsToReagree, discarded: [] } : { step: 'provider', error: null };
}

export function termsKey(terms: Pick<TermsResponse, 'kind' | 'version'>): string {
  return `${terms.kind}:${terms.version}`;
}

/** 필수 약관을 모두 골랐는가. 다음 버튼 활성 조건(SCR-10 필수 약관 미동의 시 다음 버튼 비활성) */
export function allRequiredAgreed(terms: readonly TermsResponse[], agreed: ReadonlySet<string>): boolean {
  return terms.filter((item) => item.required).every((item) => agreed.has(termsKey(item)));
}

export function toAgreements(terms: readonly TermsResponse[], agreed: ReadonlySet<string>): AgreementInput[] {
  return terms.map((item) => ({ kind: item.kind, version: item.version, agreed: agreed.has(termsKey(item)) }));
}

export function signupReducer(state: SignupState, event: SignupEvent): SignupState {
  switch (event.type) {
    case 'LOGGED_IN':
      if (event.member.termsReagreementRequired) {
        return { step: 'reagree', terms: event.member.termsToReagree, discarded: event.discarded };
      }
      return event.discarded.length > 0 ? { step: 'notice', discarded: event.discarded } : { step: 'finished', reagreed: null };
    case 'SIGNUP_NEEDED':
      return { step: 'terms', ticket: event.ticket, terms: event.terms };
    case 'TERMS_CONFIRMED':
      return state.step === 'terms'
        ? { step: 'sensitivity', ticket: state.ticket, terms: state.terms, agreements: event.agreements }
        : state;
    case 'BACK':
      return state.step === 'sensitivity' ? { step: 'terms', ticket: state.ticket, terms: state.terms } : state;
    case 'SIGNED_UP':
      return { step: 'done', nickname: event.nickname, discarded: event.discarded };
    case 'TICKET_INVALID':
      return { step: 'provider', error: event.message };
    case 'REAGREED':
      if (state.step !== 'reagree') {
        return state;
      }
      return state.discarded.length > 0 ? { step: 'notice', discarded: state.discarded } : { step: 'finished', reagreed: true };
    case 'LATER':
      return state.step === 'reagree' ? { step: 'finished', reagreed: false } : state;
    case 'ACK':
      return state.step === 'done' || state.step === 'notice' ? { step: 'finished', reagreed: null } : state;
  }
}
