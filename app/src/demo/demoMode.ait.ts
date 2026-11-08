// 앱인토스(토스 미니앱) 빌드에는 데모 모드가 없다. 가짜 서버·더미 데이터를 번들에 넣지 않으려고 이 파일이 대신한다.
export const DEMO_ENTRY_PATH = '/preview';
export const DEMO_MODE = false;
export const demoFetch: typeof fetch | undefined = undefined;
export const demoStorage = null;
export function exitDemo(): void {}
