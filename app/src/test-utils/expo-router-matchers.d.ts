// expo-router/testing-library가 등록하는 Jest 매처의 타입 (SDK 57 빌드에 타입 선언이 없다)
declare global {
  namespace jest {
    interface Matchers<R> {
      toHavePathname(pathname: string): R;
      toHavePathnameWithParams(pathname: string): R;
      toHaveSegments(segments: string[]): R;
    }
  }
}

export {};
