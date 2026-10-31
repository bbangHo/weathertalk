// 하단 탭 공통 크기. 가운데 남기기 버튼도 같은 값을 써서 다른 탭과 줄을 맞춘다.
import type { TextStyle, ViewStyle } from 'react-native';

import { colors } from '@/theme';

export const TAB_ICON_SIZE = 24;

export const tabLabelStyle: TextStyle = { fontSize: 11, lineHeight: 14, fontWeight: '600' };

/** A′ 떠 있는 둥근 탭바: 반투명 흰 판, 빛 받는 테두리, 옅은 그림자 */
export const floatingTabBarStyle: ViewStyle = {
  position: 'relative',
  marginHorizontal: 20,
  height: 68,
  paddingTop: 6,
  paddingBottom: 6,
  paddingHorizontal: 8,
  borderRadius: 999,
  borderTopWidth: 1,
  borderWidth: 1,
  borderColor: colors.glassEdge,
  backgroundColor: 'rgba(255, 255, 255, 0.92)',
  shadowColor: '#1E3A66',
  shadowOpacity: 0.12,
  shadowRadius: 16,
  shadowOffset: { width: 0, height: 4 },
  elevation: 6,
};

/** 탭 한 칸: 고른 탭은 연한 파랑 알약 바탕(tabBarActiveBackgroundColor)이 보이게 둥글게 */
export const tabItemStyle: ViewStyle = { borderRadius: 999, marginHorizontal: 2, overflow: 'hidden' };
