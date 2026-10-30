// 앱인토스(토스 미니앱)에서 외부 문서 열기. 미니앱 WebView 안에서 다른 도메인을 그리면 안 되므로(출시 체크리스트)
// 토스 앱이 외부 브라우저로 연다.
import { Device } from '@apps-in-toss/web-framework';

export async function openExternalLink(url: string): Promise<void> {
  await Device.openURL(url);
}
