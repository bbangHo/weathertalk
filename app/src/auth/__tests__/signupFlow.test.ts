// SCR-10 단계 전이(신규 / 기존 / 재동의 / 가입 티켓 무효)와 로그인 직후 처리 순서(§6.2)
import type { MemberSummary, TermsResponse } from '../api';
import { completeLogin, type PostLoginDeps } from '../completeLogin';
import {
  allRequiredAgreed,
  initialSignupState,
  signupReducer,
  type SignupEvent,
  type SignupState,
  termsKey,
  toAgreements,
} from '../signupMachine';

const service: TermsResponse = {
  kind: 'SERVICE',
  version: 2,
  title: '서비스 이용약관',
  required: true,
  effectiveAt: '2026-10-01T00:00:00Z',
  contentUrl: 'https://example.test/terms',
};
const privacy: TermsResponse = { ...service, kind: 'PRIVACY', version: 1, title: '개인정보 처리방침' };
const marketing: TermsResponse = { ...service, kind: 'LOCATION_OPTIONAL', version: 1, title: '선택 약관', required: false };

function member(overrides: Partial<MemberSummary> = {}): MemberSummary {
  return {
    memberId: 'm1',
    nickname: '맑은하늘12',
    temperatureSensitivity: 'NORMAL',
    termsReagreementRequired: false,
    termsToReagree: [],
    ...overrides,
  };
}

function run(state: SignupState, ...events: SignupEvent[]): SignupState {
  return events.reduce(signupReducer, state);
}

describe('가입 단계 (07 §3 규칙 1·2)', () => {
  it('신규: 제공자 인증 → 약관 → 민감도 → 완료(닉네임 안내) → 복귀', () => {
    let state = run(initialSignupState('signup'), { type: 'SIGNUP_NEEDED', ticket: 't1', terms: [service, marketing] });
    expect(state).toEqual({ step: 'terms', ticket: 't1', terms: [service, marketing] });

    const agreements = toAgreements([service, marketing], new Set([termsKey(service)]));
    expect(agreements).toEqual([
      { kind: 'SERVICE', version: 2, agreed: true },
      { kind: 'LOCATION_OPTIONAL', version: 1, agreed: false },
    ]);
    state = run(state, { type: 'TERMS_CONFIRMED', agreements });
    expect(state).toMatchObject({ step: 'sensitivity', ticket: 't1', agreements });

    state = run(state, { type: 'SIGNED_UP', nickname: '맑은하늘12', discarded: [] });
    expect(state).toEqual({ step: 'done', nickname: '맑은하늘12', discarded: [] });
    expect(run(state, { type: 'ACK' })).toEqual({ step: 'finished', reagreed: null });
  });

  it('필수 약관을 모두 골라야 다음으로 간다(선택 약관은 무관)', () => {
    const terms = [service, privacy, marketing];
    expect(allRequiredAgreed(terms, new Set([termsKey(service)]))).toBe(false);
    expect(allRequiredAgreed(terms, new Set([termsKey(service), termsKey(privacy)]))).toBe(true);
  });

  it('기존 회원은 약관·민감도를 건너뛰고 바로 복귀한다', () => {
    expect(run(initialSignupState('signup'), { type: 'LOGGED_IN', member: member(), discarded: [] })).toEqual({
      step: 'finished',
      reagreed: null,
    });
  });

  it('기존 회원이라도 옮기지 못한 관심 동네가 있으면 먼저 알린다', () => {
    const state = run(initialSignupState('signup'), { type: 'LOGGED_IN', member: member(), discarded: ['N7'] });
    expect(state).toEqual({ step: 'notice', discarded: ['N7'] });
  });

  it('재동의가 필요한 회원은 바뀐 약관만 보여주고, "나중에"면 닫는다', () => {
    const state = run(initialSignupState('signup'), {
      type: 'LOGGED_IN',
      member: member({ termsReagreementRequired: true, termsToReagree: [service] }),
      discarded: [],
    });
    expect(state).toEqual({ step: 'reagree', terms: [service], discarded: [] });
    expect(run(state, { type: 'LATER' })).toEqual({ step: 'finished', reagreed: false });
    expect(run(state, { type: 'REAGREED' })).toEqual({ step: 'finished', reagreed: true });
  });

  it('재동의 모드로 열면 바로 재동의 단계', () => {
    expect(initialSignupState('reagree', [privacy])).toEqual({ step: 'reagree', terms: [privacy], discarded: [] });
  });

  it('가입 티켓이 무효면 로그인부터 다시 한다', () => {
    const state = run(
      initialSignupState('signup'),
      { type: 'SIGNUP_NEEDED', ticket: 't1', terms: [service] },
      { type: 'TERMS_CONFIRMED', agreements: [] },
      { type: 'TICKET_INVALID', message: '다시 로그인해 주세요.' },
    );
    expect(state).toEqual({ step: 'provider', error: '다시 로그인해 주세요.' });
  });
});

describe('로그인 직후 처리 (07 §6.2)', () => {
  const tokens = {
    accessToken: 'a',
    accessTokenExpiresAt: '2026-10-16T00:00:00Z',
    refreshToken: 'r',
    refreshTokenExpiresAt: '2026-11-16T00:00:00Z',
  };

  function fakeDeps(overrides: Partial<PostLoginDeps> = {}) {
    const order: string[] = [];
    const step = (name: string) => jest.fn(async () => {
      order.push(name);
    });
    const deps: PostLoginDeps = {
      saveTokens: step('saveTokens'),
      syncDevice: step('syncDevice'),
      mergeInterests: jest.fn(async () => {
        order.push('mergeInterests');
        return { status: 'merged' as const, interests: [], skipped: [], discarded: ['N6'] };
      }),
      clearGuestSensitivity: step('clearGuestSensitivity'),
      judgeCurrent: step('judgeCurrent'),
      onMember: jest.fn(),
      ...overrides,
    };
    return { deps, order };
  }

  it('토큰 저장 → 기기 확인 → 관심 동네 이전 → 비회원 민감도 삭제 → 현재 동네 판정 순서', async () => {
    const { deps, order } = fakeDeps();
    const result = await completeLogin(tokens, member(), deps);
    expect(order).toEqual(['saveTokens', 'syncDevice', 'mergeInterests', 'clearGuestSensitivity', 'judgeCurrent']);
    expect(result).toEqual({ discarded: ['N6'], failedSteps: [] });
    expect(deps.onMember).toHaveBeenCalledWith(member());
  });

  it('부가 단계가 실패해도 로그인은 완료되고 나머지 단계는 계속한다', async () => {
    const { deps, order } = fakeDeps({
      syncDevice: jest.fn(async () => {
        throw new Error('offline');
      }),
      mergeInterests: jest.fn(async () => ({ status: 'failed' as const, error: new Error('x') })),
    });
    const result = await completeLogin(tokens, member(), deps);
    expect(order).toEqual(['saveTokens', 'clearGuestSensitivity', 'judgeCurrent']);
    expect(result).toEqual({ discarded: [], failedSteps: ['syncDevice', 'mergeInterests'] });
  });

  it('토큰 저장이 실패하면 로그인 실패다', async () => {
    const { deps } = fakeDeps({
      saveTokens: jest.fn(async () => {
        throw new Error('keystore');
      }),
    });
    await expect(completeLogin(tokens, member(), deps)).rejects.toThrow('keystore');
    expect(deps.syncDevice).not.toHaveBeenCalled();
  });
});
