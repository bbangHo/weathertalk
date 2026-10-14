// 07 §7.3 알림 종류 → 이동 화면
import { hrefForPushData, routes } from '../routes';

describe('푸시 데이터 → 경로', () => {
  it('동네 알림은 그 동네의 홈으로, 섹션 강조를 함께 넘긴다', () => {
    expect(hrefForPushData({ kind: 'DAILY_WEATHER', target: 'home', neighborhoodId: 'n1' })).toEqual({
      pathname: '/',
      params: { neighborhoodId: 'n1' },
    });
    expect(hrefForPushData({ target: 'home.hourly', neighborhoodId: 'n1' })).toEqual({
      pathname: '/',
      params: { neighborhoodId: 'n1', focus: 'hourly' },
    });
    expect(hrefForPushData({ target: 'home.field-report', neighborhoodId: 'n1' })).toEqual({
      pathname: '/',
      params: { neighborhoodId: 'n1', focus: 'field-report' },
    });
  });

  it('댓글 알림은 게시글 상세의 댓글 영역으로', () => {
    expect(hrefForPushData({ kind: 'COMMENT', target: 'post.detail', postId: 'p 1' })).toEqual({
      pathname: '/community/p%201',
      params: { focus: 'comments' },
    });
  });

  it('알 수 없는 종류·필드 누락은 홈으로', () => {
    expect(hrefForPushData({ target: 'unknown' })).toBe('/');
    expect(hrefForPushData({ target: 'post.detail' })).toBe('/');
    expect(hrefForPushData(null)).toBe('/');
  });

  it('작성·검색·가입은 루트 모달 경로다', () => {
    expect(routes.feelingRecord()).toBe('/feeling-record');
    expect(routes.fieldReport()).toBe('/field-report');
    expect(routes.postEditor()).toBe('/post-editor');
    expect(routes.signup()).toBe('/signup');
    expect(routes.neighborhoodSearch({ fromOnboarding: true })).toEqual({
      pathname: '/neighborhood-search',
      params: { fromOnboarding: '1' },
    });
  });
});
