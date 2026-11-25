// OS 위치 권한·좌표 (07 §2 규칙 3·5, ADR 0009). 좌표는 기기 안 동네 판정(localNeighborhood)에만 쓰고 저장·로그·전송하지 않는다.
import * as Location from 'expo-location';

import { DEMO_MODE } from '@/demo/demoMode';
import type { LocationPermission } from '@/entry/entryDecision';

import type { DeviceCoords } from './localNeighborhood';

export type PermissionResult = {
  permission: LocationPermission;
  /** 거부 상태에서 OS 창을 다시 띄울 수 있는가. false면 설정 앱으로 안내한다. */
  canAskAgain: boolean;
};

function toPermission(status: Location.PermissionStatus): LocationPermission {
  if (status === Location.PermissionStatus.GRANTED) {
    return 'granted';
  }
  return status === Location.PermissionStatus.DENIED ? 'denied' : 'undetermined';
}

/** 권한 창을 띄우지 않고 상태만 본다. 확인 실패는 권한 없음으로 본다. */
export async function getLocationPermission(): Promise<PermissionResult> {
  if (DEMO_MODE) {
    return { permission: 'granted', canAskAgain: true };
  }
  try {
    const response = await Location.getForegroundPermissionsAsync();
    return { permission: toPermission(response.status), canAskAgain: response.canAskAgain };
  } catch {
    return { permission: 'undetermined', canAskAgain: true };
  }
}

/** OS 권한 창. 첫 실행 안내의 "위치 사용하기"·동네 전환의 "켜기" 뒤에서만 부른다(규칙 3). */
export async function requestLocationPermission(): Promise<PermissionResult> {
  if (DEMO_MODE) {
    return { permission: 'granted', canAskAgain: true };
  }
  try {
    const response = await Location.requestForegroundPermissionsAsync();
    return { permission: toPermission(response.status), canAskAgain: response.canAskAgain };
  } catch {
    return { permission: 'denied', canAskAgain: false };
  }
}

/**
 * 동네 판정용 좌표 한 번. iOS '대략적 위치'만 허용돼도 그대로 쓴다 — 보기용 판정이라 막지 않는다(07 §9-5 미결).
 * 정확도는 함께 돌려줘 쓰기 직전 정확도 기준(writeLocation)에 쓴다.
 */
export async function getLocationForJudgement(): Promise<DeviceCoords> {
  if (DEMO_MODE) {
    // 데모: 브라우저 위치를 묻지 않고 역삼역 근처로 둔다(기기 안 판정으로 역삼1동).
    return { latitude: 37.5007, longitude: 127.0365, accuracyMeters: 30 };
  }
  const position = await Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.Balanced });
  const accuracy = position.coords.accuracy;
  return {
    latitude: position.coords.latitude,
    longitude: position.coords.longitude,
    accuracyMeters: typeof accuracy === 'number' && Number.isFinite(accuracy) && accuracy >= 0 ? accuracy : null,
  };
}
