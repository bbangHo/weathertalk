// 앱인토스(토스 미니앱) 보안 저장소 백엔드. APP_TARGET=ait 빌드에서만 metro.config.js가 이 파일을 고른다.
// 토스 앱이 미니앱마다 따로 두는 저장소(Storage)에 쓴다.
import { Storage } from '@apps-in-toss/web-framework';

export async function getItemAsync(key: string): Promise<string | null> {
  return Storage.getItem(key);
}

export async function setItemAsync(key: string, value: string): Promise<void> {
  await Storage.setItem(key, value);
}

export async function deleteItemAsync(key: string): Promise<void> {
  await Storage.removeItem(key);
}
