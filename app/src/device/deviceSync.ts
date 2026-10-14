// 기기 확인 호출 시점 (07 §2-11, §7.1-2·3, contracts §2.7)
// 앱 시작·포그라운드 복귀는 AppLifecycle이, 로그인·로그아웃 직후는 A2/A5가 이 함수 하나로 부른다.
// 실패해도 화면을 막지 않는다(07 §5 부가 작업 분리) — 다음 호출 때 서버가 다시 맞춘다.
import { Platform } from 'react-native';

import type { ApiClient } from '@/api/client';
import { confirmDevice, type DeviceConfirmResponse, type OsNotificationPermission } from '@/api/device';
import { generalStore } from '@/storage';

export type DeviceSyncOptions = {
  /** 푸시 토큰(A5). 주지 않으면 마지막으로 보낸 토큰 또는 없음 */
  pushToken?: string | null;
  /** OS 알림 권한(A5). 아직 묻지 않았으면 UNKNOWN */
  osPermission?: OsNotificationPermission;
};

let queue: Promise<unknown> = Promise.resolve();

/**
 * 기기 확인을 순서대로 한 번씩 보낸다. 로그인 직후처럼 앞 호출이 진행 중이어도 새 요청자 상태로 다시 보내야 하므로
 * 합치지 않고 줄을 세운다. 결과는 성공 시 응답, 실패·지원하지 않는 플랫폼이면 null.
 */
export function syncDevice(api: ApiClient, options: DeviceSyncOptions = {}): Promise<DeviceConfirmResponse | null> {
  const run = queue.then(() => send(api, options));
  queue = run.catch(() => undefined);
  return run;
}

async function send(api: ApiClient, options: DeviceSyncOptions): Promise<DeviceConfirmResponse | null> {
  const platform = Platform.OS === 'ios' ? 'IOS' : Platform.OS === 'android' ? 'ANDROID' : null;
  if (!platform) {
    return null;
  }
  try {
    const pushToken =
      options.pushToken !== undefined
        ? options.pushToken
        : ((await generalStore.get('pushTokenRegistration'))?.pushToken ?? null);
    return await confirmDevice(api, {
      platform,
      pushToken,
      osPermission: options.osPermission ?? 'UNKNOWN',
    });
  } catch {
    return null;
  }
}
