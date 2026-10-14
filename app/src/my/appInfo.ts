// 앱 버전 표시 (07 SCR-09 메뉴, §8 #18 버전 하드코딩 금지): 설치된 빌드 정보에서 읽는다.
import * as Application from 'expo-application';
import Constants from 'expo-constants';

/** "1.0.0 (12)". 네이티브 정보가 없으면(웹·Expo Go) 앱 설정 버전, 그것도 없으면 null */
export function appVersionText(): string | null {
  const version = Application.nativeApplicationVersion ?? Constants.expoConfig?.version ?? null;
  if (!version) {
    return null;
  }
  const build = Application.nativeBuildVersion;
  return build ? `${version} (${build})` : version;
}
