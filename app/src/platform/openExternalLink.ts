// 외부 문서(약관·개인정보처리방침) 열기 — 스토어 앱은 앱 안 브라우저. 앱인토스 빌드는 openExternalLink.ait.ts가 대신한다.
import * as WebBrowser from 'expo-web-browser';

export async function openExternalLink(url: string): Promise<void> {
  await WebBrowser.openBrowserAsync(url);
}
