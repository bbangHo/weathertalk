/**
 * 사전 서명 PUT 헤더. 서버가 준 헤더(소문자 content-type 포함)를 그대로 쓰고, 형식이 없을 때만 기본값을 붙인다.
 * 대소문자가 다른 같은 헤더를 둘 다 넣으면 fetch가 "image/jpeg, image/jpeg"로 합쳐 서명이 깨진다(토스 실기기에서 403).
 */
export function uploadHeaders(signed: Record<string, string>, contentType: string): Record<string, string> {
  const hasType = Object.keys(signed).some((name) => name.toLowerCase() === 'content-type');
  return hasType ? { ...signed } : { 'Content-Type': contentType, ...signed };
}
