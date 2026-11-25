// 앱인토스(토스 미니앱) 빌드 설정. `npm run build:ait`가 웹 빌드(dist) 뒤에 `ait build`로 이 파일을 읽어 .ait를 만든다.
// 플레이(네이티브) 빌드는 이 파일을 쓰지 않는다.
import { defineConfig } from '@apps-in-toss/web-framework/config';

export default defineConfig({
  // 콘솔에 등록된 appName과 같아야 업로드된다(등록 후 바꿀 수 없음).
  appName: 'weather-talk-talk',
  brand: {
    primaryColor: '#2F6FED', // theme/tokens.ts의 primary
  },
  permissions: [
    { name: 'geolocation', access: 'access' },
    { name: 'photos', access: 'read' },
  ],
  webBundleDir: 'dist',
});
