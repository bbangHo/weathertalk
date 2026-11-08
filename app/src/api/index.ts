// 앱 전역 API 클라이언트 인스턴스
import { API_BASE_URL } from '@/config/env';
import { demoFetch } from '@/demo/demoMode';
import { sessionStore } from '@/session/sessionStore';
import { secureStore } from '@/storage';

import { createApiClient } from './client';
import { createApiEvents } from './events';

export const apiEvents = createApiEvents();

export const api = createApiClient({
  baseUrl: API_BASE_URL,
  session: sessionStore,
  getDeviceCredential: () => secureStore.get('deviceCredential'),
  events: apiEvents,
  // 데모 모드(/preview)면 진짜 서버 대신 메모리 가짜 서버로 보낸다.
  fetchFn: demoFetch,
});

export * from './errors';
export { createApiClient, type ApiClient, type RequestOptions, type TokenResponse } from './client';
export { createApiEvents, type ApiEventMap, type ApiEventType, type ApiEvents } from './events';
export { newIdempotencyKey, createIdempotencyKeyHolder, useIdempotencyKey, IDEMPOTENCY_HEADER } from './idempotency';
export { confirmDevice, type ConfirmDeviceInput, type DeviceConfirmResponse } from './device';
