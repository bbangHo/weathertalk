// 디자인 토큰. 라이트 모드만(app.json userInterfaceStyle: light).
// 홈 시안 D(테두리 없는 흰 카드 + 연회색 바탕, 파랑 강조 하나, 굵은 글씨로 위계)를 따른다.

export const colors = {
  primary: '#2F6FED',
  primaryPressed: '#2459C4',
  onPrimary: '#FFFFFF',
  // 연한 강조 배경(선택된 항목 바탕, 남기기 시트 아이콘 바탕 등)
  primarySoft: '#E8F3FF',

  background: '#F2F4F6',
  surface: '#FFFFFF',
  // 흰 카드 위·회색 바탕 위 어디서나 보이는 보조 버튼 바탕
  surfaceMuted: '#E8EBEF',
  border: '#E5E8EB',

  textPrimary: '#191F28',
  textSecondary: '#4E5968',
  textDisabled: '#A3ACB9',

  danger: '#D93B3B',
  warning: '#E59A1A',
  success: '#2E9E5B',

  // 민감도 그룹 강조색 (SCR-01 3그룹 카드)
  heatSensitive: '#F06A3A',
  normal: '#2F6FED',
  coldSensitive: '#3AA0F0',

  disabledBackground: '#E9EDF3',

  // 날씨 아이콘 상태별 색
  weatherSun: '#F2A516',
  weatherMoon: '#5B6BC0',
  weatherCloud: '#8A94A6',
  weatherRain: '#2F6FED',
  weatherSnow: '#5FA8E8',

  // 홈 A′(애플 스타일): 하늘 그라데이션 바탕 위 흰 반투명 카드
  skyTop: '#9CCBFF',
  skyMid: '#D6EAFF',
  glass: 'rgba(255, 255, 255, 0.78)',
  glassEdge: 'rgba(255, 255, 255, 0.7)',
  // 예보 vs 주민 배너(색으로 뜻을 전하므로 불투명)
  warningSoft: '#FFF4E5',
  warningText: '#8A4B08',
} as const;

export const spacing = {
  xs: 4,
  sm: 8,
  md: 12,
  lg: 16,
  /** 카드 안쪽 여백 */
  card: 20,
  xl: 24,
  xxl: 32,
} as const;

export const radii = {
  sm: 6,
  /** 버튼·입력칸 */
  md: 14,
  /** 카드 */
  lg: 24,
  pill: 999,
} as const;

export const typography = {
  display: { fontSize: 28, lineHeight: 36, fontWeight: '800' },
  /** 탭 첫 화면 큰 제목(홈 동네 이름) */
  largeTitle: { fontSize: 34, lineHeight: 41, fontWeight: '800', letterSpacing: -0.7 },
  /** 홈 날씨 카드의 큰 기온: 가늘게, 자간을 좁혀 */
  hero: { fontSize: 96, lineHeight: 100, fontWeight: '300', letterSpacing: -4 },
  title: { fontSize: 22, lineHeight: 30, fontWeight: '800' },
  heading: { fontSize: 18, lineHeight: 26, fontWeight: '700' },
  body: { fontSize: 15, lineHeight: 22, fontWeight: '400' },
  caption: { fontSize: 13, lineHeight: 18, fontWeight: '400' },
  button: { fontSize: 15, lineHeight: 20, fontWeight: '700' },
} as const;

export type TypographyVariant = keyof typeof typography;
