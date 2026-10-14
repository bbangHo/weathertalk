// 위치 권한 사전 안내 거절 이력 (07 §2 규칙 3, §6.1 권한 사전 안내 이력: 권한 허용 시 지움)
import { getAppParams } from '@/config/appParams';
import { canShowPreprompt } from '@/entry/entryDecision';
import { generalStore } from '@/storage';

export async function recordLocationDeclined(now: Date = new Date()): Promise<void> {
  const history = (await generalStore.get('permissionPrepromptHistory')) ?? {};
  await generalStore.set('permissionPrepromptHistory', { ...history, locationDeclinedAt: now.toISOString() });
}

export async function clearLocationDeclined(): Promise<void> {
  const history = await generalStore.get('permissionPrepromptHistory');
  if (!history?.locationDeclinedAt) {
    return;
  }
  const { locationDeclinedAt: _removed, ...rest } = history;
  await generalStore.set('permissionPrepromptHistory', rest);
}

/** 위치가 필요한 시점에 사전 안내를 먼저 띄워도 되는가(거절 후 app.permission-preprompt-cooldown 경과) */
export async function canOfferLocationPreprompt(now: number = Date.now()): Promise<boolean> {
  const history = await generalStore.get('permissionPrepromptHistory');
  return canShowPreprompt(history?.locationDeclinedAt, now, getAppParams().permissionPrepromptCooldownMs);
}
