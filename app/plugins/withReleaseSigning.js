// 릴리스 서명: 키 정보는 저장소 밖 properties 파일에서 읽는다(storeFile·storePassword·keyAlias·keyPassword).
// 파일 경로는 ~/.gradle/gradle.properties 의 WEATHERTALK_UPLOAD_KEY_PROPERTIES 로 준다. 없으면 기본(debug 서명) 그대로.
const { withAppBuildGradle } = require('expo/config-plugins');

const PROP = 'WEATHERTALK_UPLOAD_KEY_PROPERTIES';

const RELEASE_SIGNING = `
        release {
            if (findProperty('${PROP}')) {
                def keyProps = new Properties()
                file(findProperty('${PROP}')).withInputStream { keyProps.load(it) }
                storeFile file(keyProps['storeFile'])
                storePassword keyProps['storePassword']
                keyAlias keyProps['keyAlias']
                keyPassword keyProps['keyPassword']
            }
        }`;

function addReleaseSigning(gradle) {
  if (gradle.includes(PROP)) return gradle;
  const withConfig = gradle.replace(/signingConfigs \{/, (m) => m + RELEASE_SIGNING);
  // buildTypes.release 안의 debug 서명만 바꾼다(템플릿 주석 바로 아래 줄)
  const out = withConfig.replace(
    /(signed-apk-android\.\s*\n\s*)signingConfig signingConfigs\.debug/,
    `$1signingConfig findProperty('${PROP}') ? signingConfigs.release : signingConfigs.debug`,
  );
  if (out === withConfig || withConfig === gradle) {
    throw new Error('withReleaseSigning: app/build.gradle 템플릿이 바뀌어 서명 설정을 넣지 못했어요');
  }
  return out;
}

module.exports = (config) =>
  withAppBuildGradle(config, (c) => {
    c.modResults.contents = addReleaseSigning(c.modResults.contents);
    return c;
  });
module.exports.addReleaseSigning = addReleaseSigning;
