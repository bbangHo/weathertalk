// 홈 날씨 표시 문구 (07 §5 값 없음 표시, 03 §3 용어). 값 해석·판정은 하지 않고 받은 값을 글자로만 바꾼다.
import type { AirGrade, DayNight, Freshness, PrecipitationType, SkyCondition } from './types';

export const NO_DATA = '정보 없음';

const SKY_LABEL: Record<SkyCondition, string> = { CLEAR: '맑음', MOSTLY_CLOUDY: '구름많음', OVERCAST: '흐림' };
const PRECIPITATION_LABEL: Record<PrecipitationType, string> = {
  NONE: '없음',
  RAIN: '비',
  RAIN_SNOW: '비·눈',
  SNOW: '눈',
  SHOWER: '소나기',
};
const AIR_GRADE_LABEL: Record<AirGrade, string> = { GOOD: '좋음', MODERATE: '보통', BAD: '나쁨', VERY_BAD: '매우나쁨' };

/** 하늘. 낮·밤은 weather 값으로만 붙인다(앱이 시각으로 판정하지 않음, 07 §1.5). */
export function skyText(sky: SkyCondition | null, dayNight: DayNight | null = null): string {
  if (!sky) {
    return NO_DATA;
  }
  if (sky === 'CLEAR' && dayNight) {
    return dayNight === 'NIGHT' ? '맑음(밤)' : '맑음(낮)';
  }
  return SKY_LABEL[sky];
}

export function precipitationTypeText(type: PrecipitationType | null): string {
  return type ? PRECIPITATION_LABEL[type] : NO_DATA;
}

/** 대기질 등급. 값이 없으면 '정보 없음' — '좋음'으로 채우지 않는다(07 §8 #20). */
export function airGradeText(grade: AirGrade | null | undefined): string {
  return grade ? AIR_GRADE_LABEL[grade] : NO_DATA;
}

/** 기온: 소수 첫째 자리 반올림(반올림은 표시 측, 03 §3). */
export function temperatureText(value: number | null, digits: 0 | 1 = 1): string {
  if (value === null || !Number.isFinite(value)) {
    return NO_DATA;
  }
  const rounded = digits === 0 ? Math.round(value) : Math.round(value * 10) / 10;
  return `${Object.is(rounded, -0) ? 0 : rounded}°`;
}

export function percentText(value: number | null): string {
  return value === null || !Number.isFinite(value) ? NO_DATA : `${Math.round(value)}%`;
}

export function windText(value: number | null): string {
  return value === null || !Number.isFinite(value) ? NO_DATA : `${Math.round(value * 10) / 10}m/s`;
}

/** 대기질 수치는 가공하지 않고 받은 값 그대로(에어코리아 제3유형 변경금지). */
export function rawNumberText(value: number | null | undefined, unit: string): string {
  return value === null || value === undefined ? NO_DATA : `${value}${unit}`;
}

const KST_OFFSET_MS = 9 * 60 * 60 * 1000;

/** ISO 시각 → 한국 시각 HH:mm. 기기 시간대와 무관하게 서비스 기준(KST)으로 보여준다. */
export function kstTimeText(iso: string | null): string | null {
  if (!iso) {
    return null;
  }
  const ms = Date.parse(iso);
  if (!Number.isFinite(ms)) {
    return null;
  }
  const kst = new Date(ms + KST_OFFSET_MS);
  return `${String(kst.getUTCHours()).padStart(2, '0')}:${String(kst.getUTCMinutes()).padStart(2, '0')}`;
}

const WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토'];

/** YYYY-MM-DD → "10월 15일 (목)" */
export function dateLabel(date: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date);
  if (!match) {
    return date;
  }
  const [, y, m, d] = match;
  const weekday = WEEKDAYS[new Date(Date.UTC(Number(y), Number(m) - 1, Number(d))).getUTCDay()];
  return `${Number(m)}월 ${Number(d)}일 (${weekday})`;
}

/** 신선도 안내. 판정은 weather가 했고 앱은 받은 상태를 그대로 글로 바꾼다(07 SCR-01 "지연 상태 그대로"). */
export function freshnessText(freshness: Freshness): string | null {
  switch (freshness) {
    case 'FRESH':
      return null;
    case 'DELAYED':
      return '업데이트가 늦어지고 있어요';
    case 'NONE':
      return '최신 자료를 받지 못했어요';
  }
}
