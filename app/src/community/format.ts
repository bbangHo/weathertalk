// 게시글 표시 문구 (05 BR-COM-16: 상대 시각은 앱이 만든다, 기준 시간대 Asia/Seoul)
import { SENSITIVITY_LABEL } from '@/auth/api';
import type { TemperatureSensitivity } from '@/storage';

const MINUTE = 60 * 1000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;
const KST_OFFSET_MS = 9 * HOUR;

/** "방금", "N분 전", "N시간 전", "N일 전", 일주일 넘으면 "M월 D일" */
export function relativeTimeText(iso: string, now: number = Date.now()): string {
  const at = Date.parse(iso);
  if (!Number.isFinite(at)) {
    return '';
  }
  const diff = Math.max(0, now - at);
  if (diff < MINUTE) {
    return '방금';
  }
  if (diff < HOUR) {
    return `${Math.floor(diff / MINUTE)}분 전`;
  }
  if (diff < DAY) {
    return `${Math.floor(diff / HOUR)}시간 전`;
  }
  if (diff < 7 * DAY) {
    return `${Math.floor(diff / DAY)}일 전`;
  }
  return kstDateText(iso);
}

/** "10월 15일" (한국 시각) */
export function kstDateText(iso: string): string {
  const ms = Date.parse(iso);
  if (!Number.isFinite(ms)) {
    return '';
  }
  const kst = new Date(ms + KST_OFFSET_MS);
  return `${kst.getUTCMonth() + 1}월 ${kst.getUTCDate()}일`;
}

/** "10월 15일 09:05" (한국 시각) */
export function kstDateTimeText(iso: string): string {
  const ms = Date.parse(iso);
  if (!Number.isFinite(ms)) {
    return '';
  }
  const kst = new Date(ms + KST_OFFSET_MS);
  const hh = String(kst.getUTCHours()).padStart(2, '0');
  const mm = String(kst.getUTCMinutes()).padStart(2, '0');
  return `${kst.getUTCMonth() + 1}월 ${kst.getUTCDate()}일 ${hh}:${mm}`;
}

export function sensitivityText(value: TemperatureSensitivity | null | undefined): string {
  return value ? SENSITIVITY_LABEL[value] : '';
}

/** 본문 글자 수: 서버와 같이 코드 포인트로 센다(이모지 1자, 05 §10 본문 길이). */
export function codePointLength(text: string): number {
  return Array.from(text).length;
}

export type BodyCheck = { length: number; remaining: number; valid: boolean };

/** 최소는 공백을 뺀 글자 수, 최대는 앞뒤 공백을 걷어 낸 전체 길이(05 §10). 최종 검증은 서버가 한다. */
export function checkBody(body: string, min: number, max: number): BodyCheck {
  const trimmed = body.trim();
  const length = codePointLength(trimmed);
  const nonSpace = codePointLength(trimmed.replace(/\s/g, ''));
  return { length, remaining: max - length, valid: nonSpace >= min && length <= max };
}
