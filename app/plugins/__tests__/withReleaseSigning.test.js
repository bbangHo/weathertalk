// config-plugins는 jest-expo 환경에서 ESM 의존성 때문에 못 불러와 막아 둔다(테스트 대상은 문자열 변환뿐)
jest.mock('expo/config-plugins', () => ({ withAppBuildGradle: jest.fn() }));
const { addReleaseSigning } = require('../withReleaseSigning');

// expo prebuild 템플릿의 서명 부분만 줄인 것
const TEMPLATE = `
    signingConfigs {
        debug {
            storeFile file('debug.keystore')
        }
    }
    buildTypes {
        debug {
            signingConfig signingConfigs.debug
        }
        release {
            // see https://reactnative.dev/docs/signed-apk-android.
            signingConfig signingConfigs.debug
        }
    }`;

test('release 빌드만 외부 키로 서명하고, 두 번 적용해도 같다', () => {
  const out = addReleaseSigning(TEMPLATE);
  expect(out).toContain("file(findProperty('WEATHERTALK_UPLOAD_KEY_PROPERTIES'))");
  expect(out.match(/signingConfig signingConfigs\.debug\n/g)).toHaveLength(1); // debug 빌드는 그대로
  expect(out).toContain("? signingConfigs.release : signingConfigs.debug");
  expect(addReleaseSigning(out)).toBe(out);
});

test('템플릿이 바뀌면 조용히 넘어가지 않는다', () => {
  expect(() => addReleaseSigning('android {}')).toThrow();
});
