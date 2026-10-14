// OS 위치 권한·좌표 (07 §2 규칙 3·5, ADR 0009). 좌표는 이 모듈 밖으로 동네 판정 요청 본문으로만 나가고 저장·로그하지 않는다.
import * as Location from 'expo-location';

import type { LocationPermission } from '@/entry/entryDecision';
import type { LocationRequest } from '@/neighborhood/types';

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
  try {
    const response = await Location.getForegroundPermissionsAsync();
    return { permission: toPermission(response.status), canAskAgain: response.canAskAgain };
  } catch {
    return { permission: 'undetermined', canAskAgain: true };
  }
}

/** OS 권한 창. 첫 실행 안내의 "위치 사용하기"·동네 전환의 "켜기" 뒤에서만 부른다(규칙 3). */
export async function requestLocationPermission(): Promise<PermissionResult> {
  try {
    const response = await Location.requestForegroundPermissionsAsync();
    return { permission: toPermission(response.status), canAskAgain: response.canAskAgain };
  } catch {
    return { permission: 'denied', canAskAgain: false };
  }
}

/**
 * 동네 판정용 좌표 한 번. iOS '대략적 위치'만 허용돼도 그대로 쓴다 — 보기용 판정이라 막지 않는다(07 §9-5 미결).
 * 정확도는 함께 보내 서버가 판단하게 한다.
 */
export async function getLocationForJudgement(): Promise<LocationRequest> {
  const position = await Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.Balanced });
  const accuracy = position.coords.accuracy;
  return {
    latitude: position.coords.latitude,
    longitude: position.coords.longitude,
    accuracyMeters: typeof accuracy === 'number' && Number.isFinite(accuracy) && accuracy >= 0 ? accuracy : null,
  };
}
