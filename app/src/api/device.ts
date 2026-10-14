// 기기 확인 (07 §2-11, contracts §2.7, backend notification/internal/web/DeviceController)
import { type DeviceCredential, secureStore } from '@/storage';

import type { ApiClient } from './client';
import { isApiError } from './errors';

export const DEVICE_CONFIRM_PATH = '/api/v1/notification/devices/confirm';

export type DevicePlatform = 'IOS' | 'ANDROID';
export type OsNotificationPermission = 'GRANTED' | 'DENIED' | 'UNKNOWN';

/** 백엔드 ConfirmRequest. 첫 호출(등록)이면 deviceId·deviceKey를 비운다. */
export type DeviceConfirmRequest = {
  deviceId: string | null;
  deviceKey: string | null;
  platform: DevicePlatform;
  pushToken: string | null;
  osPermission: OsNotificationPermission;
};

/** 백엔드 ConfirmResponse. deviceKey는 등록 응답에서만 온다. */
export type DeviceConfirmResponse = {
  deviceId: string;
  deviceKey: string | null;
  registered: boolean;
  status: string;
  linkedToMember: boolean;
  commentAlertEnabled: boolean;
};

export type ConfirmDeviceInput = {
  platform: DevicePlatform;
  pushToken: string | null;
  osPermission: OsNotificationPermission;
};

/**
 * 기기 확인을 호출하고 새로 발급된 기기 ID·키를 보안 저장소에 둔다.
 * 호출 시점(앱 시작·포그라운드 복귀·로그인·로그아웃 직후) 연결은 A1/A5가 한다.
 * 서버에서 기기가 사라졌으면(NOT_FOUND, DEVICE_NOT_FOUND) 저장된 값을 버리고 다시 등록한다.
 */
export async function confirmDevice(api: ApiClient, input: ConfirmDeviceInput): Promise<DeviceConfirmResponse> {
  const stored = await secureStore.get('deviceCredential');
  try {
    return await sendConfirm(api, input, stored);
  } catch (error) {
    if (stored && isApiError(error, 'NOT_FOUND') && error.reason === 'DEVICE_NOT_FOUND') {
      await secureStore.remove('deviceCredential');
      return sendConfirm(api, input, null);
    }
    throw error;
  }
}

async function sendConfirm(
  api: ApiClient,
  input: ConfirmDeviceInput,
  stored: DeviceCredential | null,
): Promise<DeviceConfirmResponse> {
  const body: DeviceConfirmRequest = {
    deviceId: stored?.deviceId ?? null,
    deviceKey: stored?.deviceKey ?? null,
    platform: input.platform,
    pushToken: input.pushToken,
    osPermission: input.osPermission,
  };
  const response = await api.post<DeviceConfirmResponse>(DEVICE_CONFIRM_PATH, body);
  if (response.deviceKey) {
    await secureStore.set('deviceCredential', { deviceId: response.deviceId, deviceKey: response.deviceKey });
  }
  return response;
}
