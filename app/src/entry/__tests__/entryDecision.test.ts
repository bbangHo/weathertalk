// 07 §2 진입 흐름·규칙 3·6
import { canShowPreprompt, decideEntry, followJudgement, shouldRejudge } from '../entryDecision';

const MIN = 60 * 1000;
const NOW = Date.parse('2026-10-15T09:00:00Z');
const base = {
  onboardingCompleted: true,
  locationPermission: 'granted' as const,
  current: null,
  lastViewedNeighborhoodId: null,
  now: NOW,
  locationRefreshIntervalMs: 10 * MIN,
};
const at = (minutesAgo: number) => new Date(NOW - minutesAgo * MIN).toISOString();

describe('decideEntry', () => {
  it('첫 실행이면 권한·저장값과 무관하게 첫 실행 안내', () => {
    expect(decideEntry({ ...base, onboardingCompleted: false, lastViewedNeighborhoodId: 'N1' })).toEqual({
      screen: 'welcome',
    });
  });

  it('권한이 있으면 마지막으로 보던 동네를 먼저 보여주고, 간격이 지났으면 재판정한다', () => {
    const decision = decideEntry({
      ...base,
      current: { neighborhoodId: 'C1', resolvedAt: at(11) },
      lastViewedNeighborhoodId: 'N1',
    });
    expect(decision).toEqual({ screen: 'home', viewingNeighborhoodId: 'N1', rejudge: true, clearCurrent: false });
  });

  it('권한이 있고 최근에 판정했으면 재판정하지 않는다', () => {
    const decision = decideEntry({ ...base, current: { neighborhoodId: 'C1', resolvedAt: at(3) } });
    expect(decision).toEqual({ screen: 'home', viewingNeighborhoodId: 'C1', rejudge: false, clearCurrent: false });
  });

  it('권한이 없으면 마지막으로 보던 동네, 재판정 없음, 남은 현재 동네는 지운다', () => {
    const decision = decideEntry({
      ...base,
      locationPermission: 'denied',
      current: { neighborhoodId: 'C1', resolvedAt: at(1) },
      lastViewedNeighborhoodId: 'N2',
    });
    expect(decision).toEqual({ screen: 'home', viewingNeighborhoodId: 'N2', rejudge: false, clearCurrent: true });
  });

  it('권한도 보던 동네도 없으면 보는 동네 없음("동네를 골라 주세요")', () => {
    const decision = decideEntry({ ...base, locationPermission: 'undetermined' });
    expect(decision).toEqual({ screen: 'home', viewingNeighborhoodId: null, rejudge: false, clearCurrent: false });
  });
});

describe('shouldRejudge (app.location-refresh-interval)', () => {
  it('판정한 적 없음 → 판정', () => {
    expect(shouldRejudge(null, NOW, 10 * MIN)).toBe(true);
  });
  it('간격 경계: 10분 미만은 생략, 10분 이상은 판정', () => {
    expect(shouldRejudge({ neighborhoodId: 'C1', resolvedAt: at(9.99) }, NOW, 10 * MIN)).toBe(false);
    expect(shouldRejudge({ neighborhoodId: 'C1', resolvedAt: at(10) }, NOW, 10 * MIN)).toBe(true);
  });
  it('깨진 시각·미래 시각이면 다시 판정', () => {
    expect(shouldRejudge({ neighborhoodId: 'C1', resolvedAt: 'x' }, NOW, 10 * MIN)).toBe(true);
    expect(shouldRejudge({ neighborhoodId: 'C1', resolvedAt: at(-5) }, NOW, 10 * MIN)).toBe(true);
  });
});

describe('followJudgement (보는 동네가 현재 동네였을 때만 따라감)', () => {
  it('보는 동네 = 이전 현재 동네 → 새 현재 동네로', () => {
    expect(followJudgement({ viewingNeighborhoodId: 'C1', previousCurrentId: 'C1', nextCurrentId: 'C2' })).toBe('C2');
  });
  it('관심·검색 동네를 보는 중이면 그대로', () => {
    expect(followJudgement({ viewingNeighborhoodId: 'I1', previousCurrentId: 'C1', nextCurrentId: 'C2' })).toBe('I1');
  });
  it('이전 현재 동네가 없으면 보던 동네를 바꾸지 않는다', () => {
    expect(followJudgement({ viewingNeighborhoodId: 'S1', previousCurrentId: null, nextCurrentId: 'C2' })).toBe('S1');
  });
  it('보는 동네가 아예 없으면 현재 동네를 보는 동네로', () => {
    expect(followJudgement({ viewingNeighborhoodId: null, previousCurrentId: null, nextCurrentId: 'C2' })).toBe('C2');
  });
});

describe('canShowPreprompt (app.permission-preprompt-cooldown)', () => {
  const DAY = 24 * 60 * MIN;
  it('거절 이력이 없으면 보여준다', () => {
    expect(canShowPreprompt(undefined, NOW, 7 * DAY)).toBe(true);
  });
  it('거절 후 7일 안에는 보여주지 않고, 지나면 다시 보여준다', () => {
    expect(canShowPreprompt(new Date(NOW - 6 * DAY).toISOString(), NOW, 7 * DAY)).toBe(false);
    expect(canShowPreprompt(new Date(NOW - 7 * DAY).toISOString(), NOW, 7 * DAY)).toBe(true);
  });
});
