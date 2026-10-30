// 가입·로그인·회원 상태 API (01 FR-MEM-02·03·08·12, backend member/internal/web/AuthController·MemberController·LoginResponse,
// member/internal/dev/DevLoginController). 응답 형식은 백엔드 레코드를 그대로 옮겼다.
import type { ApiClient } from '@/api/client';
import { DEV_ENTRY_ENABLED } from '@/config/env';
import type { TemperatureSensitivity } from '@/storage';

/** 백엔드 LoginResponse.TermsResponse */
export type TermsResponse = {
  kind: string;
  version: number;
  title: string;
  required: boolean;
  effectiveAt: string;
  contentUrl: string | null;
};

export type TokenResponse = {
  accessToken: string;
  accessTokenExpiresAt: string;
  refreshToken: string;
  refreshTokenExpiresAt: string;
};

/** 백엔드 LoginResponse.MemberSummaryResponse */
export type MemberSummary = {
  memberId: string;
  nickname: string;
  temperatureSensitivity: TemperatureSensitivity;
  termsReagreementRequired: boolean;
  termsToReagree: TermsResponse[];
};

/** 백엔드 LoginResponse. registered=true면 tokens·member, false면 signupTicket·terms */
export type LoginResponse = {
  registered: boolean;
  tokens: TokenResponse | null;
  member: MemberSummary | null;
  signupTicket: { ticket: string; expiresAt: string } | null;
  terms: TermsResponse[] | null;
};

/** 백엔드 AuthController.AgreementRequest */
export type AgreementInput = { kind: string; version: number; agreed: boolean };

/** TOSS_ANON: 앱인토스 사용자 식별키(토스 미니앱 빌드에서만) */
export type AuthProvider = 'KAKAO' | 'APPLE' | 'GOOGLE' | 'TOSS_ANON';

/** 백엔드 MemberController.MyProfileResponse */
export type MyProfile = {
  memberId: string;
  nickname: string;
  temperatureSensitivity: TemperatureSensitivity;
  email: string | null;
  providers: (AuthProvider | 'DEV')[];
  nicknameChangeAvailableAt: string | null;
  termsReagreementRequired: boolean;
  termsToReagree: TermsResponse[];
};

export type SensitivityChangeSource = 'DIRECT' | 'CALIBRATION_ACCEPTED';

export const DEV_LOGIN_PATH = '/api/v1/dev/login';

/**
 * 개발 전용 로그인(07 §4-4). 운영 빌드에서는 DEV_ENTRY_ENABLED(__DEV__)가 false라 호출 자체를 막는다.
 * 서버도 local·staging·test 프로파일에서만 이 경로를 등록한다.
 */
export function devLogin(api: ApiClient, providerUserId: string): Promise<LoginResponse> {
  if (!DEV_ENTRY_ENABLED) {
    return Promise.reject(new Error('개발 전용 로그인은 개발 빌드에서만 쓸 수 있어요.'));
  }
  return api.post<LoginResponse>(DEV_LOGIN_PATH, { providerUserId, email: null }, { auth: false });
}

/** 제공자 로그인(FR-MEM-02). token은 제공자 토큰, 앱인토스 식별키는 인증 코드 */
export function loginWithProvider(api: ApiClient, provider: AuthProvider, token: string): Promise<LoginResponse> {
  return api.post<LoginResponse>('/api/v1/auth/login', { provider, token }, { auth: false });
}

export function completeSignup(
  api: ApiClient,
  input: { signupTicket: string; agreements: AgreementInput[]; temperatureSensitivity: TemperatureSensitivity },
): Promise<LoginResponse> {
  return api.post<LoginResponse>('/api/v1/signup', input, { auth: false });
}

export function getTerms(api: ApiClient): Promise<TermsResponse[]> {
  return api.get<TermsResponse[]>('/api/v1/terms', { auth: false });
}

export function getMyProfile(api: ApiClient): Promise<MyProfile> {
  return api.get<MyProfile>('/api/v1/members/me');
}

export function reagreeTerms(api: ApiClient, agreements: AgreementInput[]): Promise<MyProfile> {
  return api.post<MyProfile>('/api/v1/members/me/terms-agreements', { agreements });
}

/** 온도 민감도 변경(FR-MEM-08). 재동의 전에도 허용되는 쓰기다(contracts §4). */
export function changeTemperatureSensitivity(
  api: ApiClient,
  temperatureSensitivity: TemperatureSensitivity,
  source: SensitivityChangeSource = 'DIRECT',
): Promise<MyProfile> {
  return api.put<MyProfile>('/api/v1/members/me/temperature-sensitivity', { temperatureSensitivity, source });
}

export const SENSITIVITY_LABEL: Record<TemperatureSensitivity, string> = {
  HEAT_SENSITIVE: '더위를 많이 탐',
  NORMAL: '보통',
  COLD_SENSITIVE: '추위를 많이 탐',
};

export const SENSITIVITY_ORDER: readonly TemperatureSensitivity[] = ['HEAT_SENSITIVE', 'NORMAL', 'COLD_SENSITIVE'];
