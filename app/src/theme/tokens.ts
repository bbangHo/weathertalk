// 디자인 토큰. 라이트 모드만(app.json userInterfaceStyle: light). 시각 디자인 확정 전 기본값이다.

export const colors = {
  primary: '#2F6FED',
  primaryPressed: '#2459C4',
  onPrimary: '#FFFFFF',

  background: '#F6F8FB',
  surface: '#FFFFFF',
  border: '#E1E6EE',

  textPrimary: '#14181F',
  textSecondary: '#5B6573',
  textDisabled: '#A3ACB9',

  danger: '#D93B3B',
  warning: '#E59A1A',
  success: '#2E9E5B',

  // 민감도 그룹 강조색 (SCR-01 3그룹 카드)
  heatSensitive: '#F06A3A',
  normal: '#2F6FED',
  coldSensitive: '#3AA0F0',

  disabledBackground: '#E9EDF3',
} as const;

export const spacing = {
  xs: 4,
  sm: 8,
  md: 12,
  lg: 16,
  xl: 24,
  xxl: 32,
} as const;

export const radii = {
  sm: 6,
  md: 10,
  lg: 16,
  pill: 999,
} as const;

export const typography = {
  title: { fontSize: 22, lineHeight: 30, fontWeight: '700' },
  heading: { fontSize: 18, lineHeight: 26, fontWeight: '600' },
  body: { fontSize: 15, lineHeight: 22, fontWeight: '400' },
  caption: { fontSize: 13, lineHeight: 18, fontWeight: '400' },
  button: { fontSize: 15, lineHeight: 20, fontWeight: '600' },
} as const;

export type TypographyVariant = keyof typeof typography;
