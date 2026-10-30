// 가입 화면의 로그인 수단 버튼(스토어 앱). 앱인토스 빌드는 providerLogin.ait.ts가 대신한다(같은 내보내기).
// 카카오·애플·구글 SDK 연결은 A5 — 그때까지 버튼만 보이고 눌리지 않는다(login=null).
import { Platform } from 'react-native';

import type { ApiClient } from '@/api/client';

import type { LoginResponse } from './api';

export type ProviderButton = {
  key: string;
  title: string;
  /** 제공자 로그인. null이면 아직 쓸 수 없어 버튼을 끈다 */
  login: ((api: ApiClient) => Promise<LoginResponse>) | null;
};

const NAMES = Platform.OS === 'ios' ? ['카카오', '애플', '구글'] : ['카카오', '구글'];

export const PROVIDER_BUTTONS: ProviderButton[] = NAMES.map((name) => ({
  key: name,
  title: `${name}로 계속하기 (준비 중)`,
  login: null,
}));
