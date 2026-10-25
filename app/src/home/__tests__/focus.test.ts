// 홈 focus 파라미터 해석과 스크롤 대상 위치 (07 §7.3, A5 푸시 이동 hrefForPushData와 같은 값)
import { hrefForPushData } from '@/navigation/routes';

import { FOCUS_TOP_MARGIN, focusScrollY, parseHomeFocus } from '../focus';

describe('parseHomeFocus', () => {
  it('아는 값만 받는다', () => {
    expect(parseHomeFocus('hourly')).toBe('hourly');
    expect(parseHomeFocus('field-report')).toBe('field-report');
    expect(parseHomeFocus('comments')).toBeNull();
    expect(parseHomeFocus(['hourly'])).toBeNull();
    expect(parseHomeFocus(undefined)).toBeNull();
    expect(parseHomeFocus('')).toBeNull();
  });

  it('푸시 이동 경로의 focus를 그대로 해석한다', () => {
    const hourly = hrefForPushData({ target: 'home.hourly', neighborhoodId: 'N1' }) as unknown as { params: { focus: string } };
    const field = hrefForPushData({ target: 'home.field-report', neighborhoodId: 'N1' }) as unknown as { params: { focus: string } };
    expect(parseHomeFocus(hourly.params.focus)).toBe('hourly');
    expect(parseHomeFocus(field.params.focus)).toBe('field-report');
  });
});

describe('focusScrollY', () => {
  const positions = { hourly: 900, 'field-report': 640 };

  it('가리킨 섹션의 자리(위 여백 제외)를 고른다', () => {
    expect(focusScrollY('field-report', positions)).toBe(640 - FOCUS_TOP_MARGIN);
    expect(focusScrollY('hourly', positions)).toBe(900 - FOCUS_TOP_MARGIN);
  });

  it('focus가 없거나 자리를 아직 모르면 null', () => {
    expect(focusScrollY(null, positions)).toBeNull();
    expect(focusScrollY('hourly', { 'field-report': 640 })).toBeNull();
  });

  it('맨 위 섹션이면 0보다 작게 가지 않는다', () => {
    expect(focusScrollY('hourly', { hourly: 4 })).toBe(0);
  });
});
