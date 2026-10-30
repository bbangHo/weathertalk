// Metro 설정. 기본은 Expo 그대로이고, 앱인토스(토스 미니앱) 빌드에서만 두 가지를 바꾼다.
//
// APP_TARGET=ait 일 때
// 1. `*.ait.ts(x)`를 같은 이름의 다른 파일(`.web.ts`, `.ts`)보다 먼저 읽는다.
//    예: storage/secureBackend.ait.ts가 secureBackend.web.ts·secureBackend.ts 대신 쓰인다.
// 2. AIT_DEVTOOLS=1 이면 @apps-in-toss/web-framework를 devtools mock으로 바꾸고 패널(오른쪽 아래 AIT 버튼)을 띄운다.
//    토스 앱 밖(일반 브라우저)에서는 SDK가 동작하지 않아서다. devtools는 Metro 플러그인이 없어 공식 README의 '수동 alias' 방식을 쓴다.
//
// 플레이(네이티브) 빌드와 jest는 APP_TARGET을 주지 않으므로 아무것도 바뀌지 않는다.
const path = require('path');
const { getDefaultConfig } = require('expo/metro-config');

const config = getDefaultConfig(__dirname);

if (process.env.APP_TARGET === 'ait') {
  const { resolver } = config;
  resolver.sourceExts = [
    ...['ts', 'tsx', 'js', 'jsx'].map((ext) => `ait.${ext}`),
    ...resolver.sourceExts,
  ];

  if (process.env.AIT_DEVTOOLS === '1') {
    const devtoolsEntry = path.resolve(__dirname, 'src/ait/devtoolsEntry.js');
    resolver.resolveRequest = (context, moduleName, platform) => {
      if (moduleName === '@apps-in-toss/web-framework') {
        return { type: 'sourceFile', filePath: devtoolsEntry };
      }
      return context.resolveRequest(context, moduleName, platform);
    };
  }
}

module.exports = config;
