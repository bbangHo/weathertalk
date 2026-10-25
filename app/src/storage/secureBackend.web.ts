// 웹 미리보기 전용 보안 저장소 백엔드. expo-secure-store는 웹 구현이 비어 있어 localStorage로 대신한다.
// 암호화되지 않으므로 웹은 개발 중 화면 확인에만 쓴다(출시 대상 아님).
export async function getItemAsync(key: string): Promise<string | null> {
  return globalThis.localStorage?.getItem(key) ?? null;
}

export async function setItemAsync(key: string, value: string): Promise<void> {
  globalThis.localStorage?.setItem(key, value);
}

export async function deleteItemAsync(key: string): Promise<void> {
  globalThis.localStorage?.removeItem(key);
}
