// 앱인토스(토스 미니앱) 로그인 수단: 사용자 식별키 하나. APP_TARGET=ait 빌드에서 providerLogin.ts 대신 쓰인다.
// 토스 앱에서 5분·1회용 인증 코드를 받아 서버로 보내면 서버가 교환 API(mTLS)로 식별키를 얻어 회원을 찾는다.
// 앱인토스 안에서는 토스 외 로그인을 쓸 수 없고, 토스 로그인은 사업자 등록 뒤에 붙인다.
import { User } from '@apps-in-toss/web-framework';

import type { ApiClient } from '@/api/client';

import { type LoginResponse, loginWithProvider } from './api';
import type { ProviderButton } from './providerLogin';

export type { ProviderButton } from './providerLogin';

async function loginWithTossAnonKey(api: ApiClient): Promise<LoginResponse> {
  const { code } = await User.createAnonymousKeyAuthCode();
  return loginWithProvider(api, 'TOSS_ANON', code);
}

export const PROVIDER_BUTTONS: ProviderButton[] = [{ key: 'toss', title: '토스로 계속하기', login: loginWithTossAnonKey }];
