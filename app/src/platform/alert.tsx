// 확인 창(iOS·Android). OS 기본 창을 그대로 쓴다. 웹(앱인토스 미니앱 포함)은 alert.web.tsx가 대신한다.
export { Alert } from 'react-native';

/** 웹에서 확인 창을 그리는 자리. 네이티브는 OS가 그리므로 아무것도 그리지 않는다. */
export function AlertHost() {
  return null;
}
