// 넓은 화면(PC 웹) 배치: 하단 탭 대신 사이드바, 홈 2단, 커뮤니티 목록|상세. 앱인토스 빌드는 wideLayout.ait.ts가 대신한다.
import { Platform, useWindowDimensions } from 'react-native';

const WIDE_MIN_WIDTH = 1024;

export function useWideLayout(): boolean {
  const { width } = useWindowDimensions();
  return Platform.OS === 'web' && width >= WIDE_MIN_WIDTH;
}
