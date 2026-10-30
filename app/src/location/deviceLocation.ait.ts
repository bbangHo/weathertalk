// 앱인토스(토스 미니앱) 위치 권한·좌표. APP_TARGET=ait 빌드에서 deviceLocation.ts 대신 쓰인다(같은 내보내기).
// 권한 창은 토스 앱이 띄운다. 좌표는 동네 판정 요청 본문으로만 나가고 저장·로그하지 않는다(ADR 0009).
import { Accuracy, Device, type PermissionStatus } from '@apps-in-toss/web-framework';

import type { LocationPermission } from '@/entry/entryDecision';
import type { LocationRequest } from '@/neighborhood/types';

export type PermissionResult = {
  permission: LocationPermission;
  /** 거부 상태에서 권한 창을 다시 띄울 수 있는가. 토스 앱은 다시 물을 수 있어 항상 true다. */
  canAskAgain: boolean;
};

function toPermission(status: PermissionStatus): LocationPermission {
  if (status === 'allowed') {
    return 'granted';
  }
  return status === 'denied' ? 'denied' : 'undetermined';
}

/** 권한 창을 띄우지 않고 상태만 본다. 확인 실패는 권한 없음으로 본다. */
export async function getLocationPermission(): Promise<PermissionResult> {
  try {
    return { permission: toPermission(await Device.getLocation.getPermission()), canAskAgain: true };
  } catch {
    return { permission: 'undetermined', canAskAgain: true };
  }
}

/** 토스 권한 창. 첫 실행 안내의 "위치 사용하기"·동네 전환의 "켜기" 뒤에서만 부른다(07 §2 규칙 3). */
export async function requestLocationPermission(): Promise<PermissionResult> {
  try {
    return { permission: toPermission(await Device.getLocation.openPermissionDialog()), canAskAgain: true };
  } catch {
    return { permission: 'denied', canAskAgain: true };
  }
}

/** 동네 판정용 좌표 한 번. 정확도는 함께 보내 서버가 판단하게 한다. */
export async function getLocationForJudgement(): Promise<LocationRequest> {
  const position = await Device.getLocation({ accuracy: Accuracy.Balanced });
  const accuracy = position.coords.accuracy;
  return {
    latitude: position.coords.latitude,
    longitude: position.coords.longitude,
    accuracyMeters: typeof accuracy === 'number' && Number.isFinite(accuracy) && accuracy >= 0 ? accuracy : null,
  };
}
