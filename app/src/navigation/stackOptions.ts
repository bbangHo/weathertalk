// 스택 헤더 공통 모양. 홈이 직접 그리는 헤더(배경색 위 굵은 제목)와 맞춘다.
import { colors, typography } from '@/theme';

export const stackScreenOptions = {
  headerTintColor: colors.textPrimary,
  headerStyle: { backgroundColor: colors.background },
  headerShadowVisible: false,
  headerTitleStyle: { fontSize: typography.heading.fontSize, fontWeight: '700' },
  contentStyle: { backgroundColor: colors.background },
} as const;
