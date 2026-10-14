// 환경별 설정 (07 §8 #22: 서버 주소 하드코딩 금지)

const DEFAULT_API_BASE_URL = 'http://localhost:8080';

function normalizeBaseUrl(value: string | undefined): string {
  const trimmed = value?.trim();
  if (!trimmed) {
    return DEFAULT_API_BASE_URL;
  }
  return trimmed.replace(/\/+$/, '');
}

// Expo는 process.env.EXPO_PUBLIC_* 를 정적으로 치환하므로 반드시 이 형태로 읽는다.
export const API_BASE_URL = normalizeBaseUrl(process.env.EXPO_PUBLIC_API_BASE_URL);

/**
 * 개발 전용 진입점(테스트 로그인 등)을 켤지. 운영 빌드에서는 __DEV__가 false라 번들에서 빠진다 (07 §4-4).
 * 개발 전용 화면을 추가할 때는 이 값으로 라우트 등록·버튼 노출을 모두 감싼다.
 */
export const DEV_ENTRY_ENABLED: boolean = __DEV__;

function optionalUrl(value: string | undefined): string | null {
  const trimmed = value?.trim();
  return trimmed ? trimmed : null;
}

/** 이용약관·개인정보처리방침 주소 (07 SCR-09, §8 #18·#22). 비어 있으면 화면에서 "준비 중"으로 보여준다. */
export const TERMS_OF_SERVICE_URL = optionalUrl(process.env.EXPO_PUBLIC_TERMS_URL);
export const PRIVACY_POLICY_URL = optionalUrl(process.env.EXPO_PUBLIC_PRIVACY_URL);
