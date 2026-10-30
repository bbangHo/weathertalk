// 앱인토스(토스 미니앱) 스택 헤더. APP_TARGET=ait 빌드에서 stackOptions.ts 대신 쓰인다.
// 토스 내비게이션 바가 뒤로가기를 그리므로 자체 뒤로가기는 숨긴다(출시 체크리스트: 두 뒤로가기 동시 노출 금지).
// 토스 뒤로가기는 브라우저 기록을 되돌리고, Expo Router 웹이 그 기록으로 화면을 되돌린다.
import { stackScreenOptions as base } from './stackOptions.base';

// headerBackVisible은 웹 스택에서 무시돼 왼쪽 자리를 비운다.
export const stackScreenOptions = { ...base, headerLeft: () => null } as const;
