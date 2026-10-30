// 앱인토스 개발용(APP_TARGET=ait AIT_DEVTOOLS=1)에서만 metro.config.js가 @apps-in-toss/web-framework 대신 읽는 파일.
// 토스 앱 밖 브라우저에서 SDK 호출을 mock으로 받고, 화면 오른쪽 아래에 AIT 패널을 띄운다.
import '@apps-in-toss/devtools/panel';

export * from '@apps-in-toss/devtools/mock';
