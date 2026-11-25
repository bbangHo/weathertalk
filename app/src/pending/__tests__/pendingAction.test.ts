// 07 §3 보류 행동: 저장·덮어쓰기·취소, 복원(좋아요만 자동 적용), 재시작 규칙, 현재 동네 재판정, 쓰기 관문
import AsyncStorage from '@react-native-async-storage/async-storage';
import * as Location from 'expo-location';

import { resetAppParams } from '@/config/appParams';
import { ensureFreshCurrentNeighborhood, isJudgementFresh, prepareWriteLocation } from '@/location/writeLocation';
import { viewingStore } from '@/neighborhood/viewingStore';
import { generalStore } from '@/storage';
import { problem } from '@/test-utils/apiClient';
import { installFetch } from '@/test-utils/globalFetch';

import {
  compareWithCurrent,
  LIKE_FAILED_NOTICE,
  NOT_CURRENT_NOTICE,
  type PendingAction,
  pendingActionStore,
  registerAutoApply,
  type RestoreDeps,
  restoredActions,
  restorePendingAction,
} from '../pendingAction';
import { resumePendingOnLaunch } from '../restore';
import { createWriteGate } from '../writeGate';

const mockedLocation = Location as jest.Mocked<typeof Location>;
const granted = { status: 'granted', granted: true, canAskAgain: true, expires: 'never' } as never;
/** jest.setup의 기본 모의 좌표(37.5, 127.0)를 기기 안에서 판정한 동네(서울 서초구 반포2동) */
const BANPO2 = '1165057000';

const record: PendingAction = {
  kind: 'feelingRecord',
  route: '/feeling-record',
  neighborhoodId: null,
  payload: { temperature: 'COLD', humidity: null, wind: 'LIGHT' },
};
const feedback: PendingAction = {
  kind: 'feedback',
  route: '/',
  neighborhoodId: 'N1',
  payload: { answer: 'COLDER', group: 'NORMAL', temperatureTag: 'JUST_RIGHT' },
};
const like: PendingAction = { kind: 'like', route: '/community', neighborhoodId: null, payload: { postId: 'p1', liked: true } };
const comment: PendingAction = {
  kind: 'comment',
  route: '/community/p1',
  neighborhoodId: 'N9',
  payload: { postId: 'p1', parentCommentId: null, body: '안녕하세요' },
};

beforeEach(async () => {
  await AsyncStorage.clear();
  viewingStore.reset();
  restoredActions.reset();
  resetAppParams();
  mockedLocation.getForegroundPermissionsAsync.mockResolvedValue({ status: 'undetermined', canAskAgain: true } as never);
});

function deps(overrides: Partial<RestoreDeps> = {}) {
  const delivered: unknown[] = [];
  const base: RestoreDeps = {
    load: () => pendingActionStore.load(),
    clear: () => pendingActionStore.clear(),
    checkCurrentNeighborhood: jest.fn(async () => 'ok' as const),
    navigate: jest.fn(),
    deliver: (restored) => delivered.push(restored),
  };
  return { deps: { ...base, ...overrides }, delivered };
}

describe('보류 행동 저장 (§3 규칙 5)', () => {
  it('하나만 보관하고 새 가입 유도가 덮어쓰며, 취소하면 지운다', async () => {
    await pendingActionStore.save(record);
    await pendingActionStore.save(feedback);
    await expect(pendingActionStore.load()).resolves.toEqual(feedback);

    await pendingActionStore.clear();
    await expect(pendingActionStore.load()).resolves.toBeNull();
  });

  it('좌표는 저장하지 않는다(저장소가 거부)', async () => {
    await expect(
      pendingActionStore.save({ ...record, payload: { ...record.payload, latitude: 37 } as never }),
    ).rejects.toThrow();
  });
});

describe('복원 (§3 규칙 4)', () => {
  it('좋아요만 등록된 처리기로 자동 적용한다', async () => {
    const apply = jest.fn(async () => undefined);
    const off = registerAutoApply('like', apply);
    await pendingActionStore.save(like);
    const { deps: d, delivered } = deps();

    const outcome = await restorePendingAction(d);

    expect(apply).toHaveBeenCalledWith(like);
    expect(outcome).toMatchObject({ status: 'restored', autoApplied: true, notice: null });
    expect(d.navigate).toHaveBeenCalledWith(like);
    expect(delivered).toHaveLength(1);
    await expect(pendingActionStore.load()).resolves.toBeNull();
    off();
  });

  it('좋아요 적용이 실패하면 알린다', async () => {
    const off = registerAutoApply('like', async () => {
      throw new Error('x');
    });
    await pendingActionStore.save(like);
    const outcome = await restorePendingAction(deps().deps);
    expect(outcome).toMatchObject({ autoApplied: false, notice: LIKE_FAILED_NOTICE });
    off();
  });

  it('체감 기록·피드백·댓글은 자동 제출하지 않고 입력만 넘긴다(좋아요 처리기도 부르지 않음)', async () => {
    const apply = jest.fn(async () => undefined);
    const off = registerAutoApply('like', apply);
    const { calls } = installFetch(() => problem(500, 'X'));
    for (const action of [record, feedback, comment]) {
      await pendingActionStore.save(action);
      const { deps: d, delivered } = deps();
      const outcome = await restorePendingAction(d);
      expect(outcome).toMatchObject({ status: 'restored', autoApplied: false, notice: null });
      expect(delivered[0]).toMatchObject({ action });
    }
    expect(apply).not.toHaveBeenCalled();
    expect(calls).toHaveLength(0);
    off();
  });

  it('현재 동네가 필요한 행동만 재판정하고, 다르거나 판정 불가면 안내를 붙인다(§3 규칙 7)', async () => {
    await pendingActionStore.save(comment);
    const commentDeps = deps();
    await restorePendingAction(commentDeps.deps);
    expect(commentDeps.deps.checkCurrentNeighborhood).not.toHaveBeenCalled();

    await pendingActionStore.save(feedback);
    const mismatch = await restorePendingAction(deps({ checkCurrentNeighborhood: async () => 'mismatch' }).deps);
    expect(mismatch).toMatchObject({ notice: NOT_CURRENT_NOTICE });

    await pendingActionStore.save(record);
    const unavailable = await restorePendingAction(deps({ checkCurrentNeighborhood: async () => 'unavailable' }).deps);
    expect(unavailable).toMatchObject({ notice: NOT_CURRENT_NOTICE, action: record });
  });

  it('보류 행동이 없으면 아무것도 하지 않는다', async () => {
    const { deps: d } = deps();
    await expect(restorePendingAction(d)).resolves.toEqual({ status: 'none' });
    expect(d.navigate).not.toHaveBeenCalled();
  });

  it('현재 동네 비교: 체감 기록은 판정만 되면, 나머지는 보던 동네와 같아야 한다', () => {
    expect(compareWithCurrent(record, 'N5')).toBe('ok');
    expect(compareWithCurrent(feedback, 'N1')).toBe('ok');
    expect(compareWithCurrent(feedback, 'N2')).toBe('mismatch');
    expect(compareWithCurrent(record, null)).toBe('unavailable');
  });
});

describe('가입 중 앱 종료 후 재시작 (§3 규칙 6)', () => {
  it('비회원이면 보류 행동만 지우고 초안은 남긴다', async () => {
    await pendingActionStore.save(record);
    const draft = { body: '초안', images: [], feelingRecordId: null, savedAt: '2026-10-15T00:00:00Z' };
    await generalStore.set('postDraft', draft);

    await expect(resumePendingOnLaunch(false)).resolves.toEqual({ status: 'none' });

    await expect(pendingActionStore.load()).resolves.toBeNull();
    await expect(generalStore.get('postDraft')).resolves.toEqual(draft);
  });

  it('회원이면 복원한다(위치 판정 불가 → 안내와 함께 입력만)', async () => {
    installFetch(() => problem(503, 'TEMPORARILY_UNAVAILABLE'));
    await pendingActionStore.save(record);
    const outcome = await resumePendingOnLaunch(true);
    expect(outcome).toMatchObject({ status: 'restored', notice: NOT_CURRENT_NOTICE });
    expect(restoredActions.take('feelingRecord')).toMatchObject({ action: record });
  });
});

describe('쓰기 직전 현재 동네 (app.write-location-max-age)', () => {
  const now = Date.parse('2026-10-15T03:00:00Z');

  it('판정 시각이 max-age 안이면 신선하다', () => {
    const fresh = { neighborhoodId: 'N1', resolvedAt: '2026-10-15T02:56:00Z' };
    const stale = { neighborhoodId: 'N1', resolvedAt: '2026-10-15T02:54:00Z' };
    expect(isJudgementFresh(fresh, now, 5 * 60_000)).toBe(true);
    expect(isJudgementFresh(stale, now, 5 * 60_000)).toBe(false);
    expect(isJudgementFresh(null, now, 5 * 60_000)).toBe(false);
  });

  it('신선하면 재판정하지 않는다', async () => {
    const { calls } = installFetch(() => problem(500, 'X'));
    await viewingStore.applyJudgement('N1', '2026-10-15T02:58:00Z');
    await expect(ensureFreshCurrentNeighborhood({ store: viewingStore, now: () => now })).resolves.toBe('N1');
    expect(calls).toHaveLength(0);
  });

  it('오래됐으면 기기 안에서 재판정하고(서버 호출 없음), 권한이 없으면 판정 불가', async () => {
    await viewingStore.applyJudgement('N1', '2026-10-15T01:00:00Z');
    const { calls } = installFetch(() => problem(500, 'X'));
    await expect(ensureFreshCurrentNeighborhood({ store: viewingStore, now: () => now })).resolves.toBeNull();

    mockedLocation.getForegroundPermissionsAsync.mockResolvedValue(granted);
    // 기본 모의 좌표(37.5, 127.0)는 서초구 반포2동. 비교용일 뿐 확인 전이라 저장하지 않는다.
    await expect(ensureFreshCurrentNeighborhood({ store: viewingStore, now: () => now })).resolves.toBe(BANPO2);
    expect(calls).toHaveLength(0);
    expect(viewingStore.getState().current?.neighborhoodId).toBe('N1');
  });

  it('다시 판정한 동네가 화면의 현재 동네와 다르면 묻고, 거절하면 보내지 않는다', async () => {
    mockedLocation.getForegroundPermissionsAsync.mockResolvedValue(granted);
    await viewingStore.applyJudgement('N1', '2026-10-15T01:00:00Z');
    const confirmChange = jest.fn(async () => false);

    const result = await prepareWriteLocation({ store: viewingStore, now: () => now, confirmChange });

    expect(result).toEqual({ status: 'cancelled' });
    expect(confirmChange).toHaveBeenCalledWith(expect.objectContaining({ id: BANPO2, eupmyeondongName: '반포2동' }));
    expect(viewingStore.getState().current?.neighborhoodId).toBe('N1');
  });

  it('다시 판정한 동네와 같으면 묻지 않고 보낸다', async () => {
    mockedLocation.getForegroundPermissionsAsync.mockResolvedValue(granted);
    await viewingStore.applyJudgement(BANPO2, '2026-10-15T01:00:00Z');
    const confirmChange = jest.fn(async () => true);

    await expect(prepareWriteLocation({ store: viewingStore, now: () => now, confirmChange })).resolves.toEqual({
      status: 'ok',
      neighborhoodId: BANPO2,
    });
    expect(confirmChange).not.toHaveBeenCalled();
  });

  it('쓰기용 좌표는 한 번만 받아 기기 안에서 재판정하고, 바뀐 동네를 확인하면 그 동네 ID만 돌려준다', async () => {
    mockedLocation.getForegroundPermissionsAsync.mockResolvedValue(granted);
    mockedLocation.getCurrentPositionAsync.mockClear();
    await viewingStore.applyJudgement('N1', '2026-10-15T01:00:00Z');
    const { calls } = installFetch(() => problem(500, 'X'));

    const result = await prepareWriteLocation({ store: viewingStore, now: () => now, confirmChange: async () => true });

    expect(result).toEqual({ status: 'ok', neighborhoodId: BANPO2 });
    expect(mockedLocation.getCurrentPositionAsync).toHaveBeenCalledTimes(1);
    expect(calls).toHaveLength(0);
    // 기기에는 좌표가 남지 않는다
    await expect(generalStore.get('currentNeighborhood')).resolves.toEqual({
      neighborhoodId: BANPO2,
      resolvedAt: expect.any(String),
    });
  });

  it('신선한 판정이면 보여준 현재 동네를 그대로 쓴다', async () => {
    mockedLocation.getForegroundPermissionsAsync.mockResolvedValue(granted);
    await viewingStore.applyJudgement('N1', '2026-10-15T02:58:00Z');

    await expect(prepareWriteLocation({ store: viewingStore, now: () => now })).resolves.toEqual({
      status: 'ok',
      neighborhoodId: 'N1',
    });
  });

  it('위치 정확도가 기준(500m)보다 나쁘거나 모르면 쓰지 않는다', async () => {
    mockedLocation.getForegroundPermissionsAsync.mockResolvedValue(granted);
    await viewingStore.applyJudgement('N1', '2026-10-15T02:58:00Z');
    const position = (accuracy: number | null) =>
      ({ coords: { latitude: 37.5, longitude: 127.0, accuracy }, timestamp: 0 }) as never;

    mockedLocation.getCurrentPositionAsync.mockResolvedValueOnce(position(500));
    await expect(prepareWriteLocation({ store: viewingStore, now: () => now })).resolves.toMatchObject({ status: 'ok' });
    mockedLocation.getCurrentPositionAsync.mockResolvedValueOnce(position(500.1));
    await expect(prepareWriteLocation({ store: viewingStore, now: () => now })).resolves.toEqual({ status: 'unavailable' });
    mockedLocation.getCurrentPositionAsync.mockResolvedValueOnce(position(null));
    await expect(prepareWriteLocation({ store: viewingStore, now: () => now })).resolves.toEqual({ status: 'unavailable' });
  });
});

describe('쓰기 관문 requireMember (§3, §2 규칙 12)', () => {
  function gate(status: 'guest' | 'member', reagree = false) {
    const saved: PendingAction[] = [];
    const opened: string[] = [];
    let required = reagree;
    const g = createWriteGate({
      sessionStatus: () => status,
      reagreementRequired: () => required,
      markReagreementRequired: () => {
        required = true;
      },
      save: async (action) => {
        saved.push(action);
      },
      openSheet: (mode) => opened.push(mode),
    });
    return { g, saved, opened };
  }

  it('비회원은 보류 행동을 저장하고 가입 시트를 연다', async () => {
    const { g, saved, opened } = gate('guest');
    await expect(g.requireMember(record)).resolves.toBe(false);
    expect(saved).toEqual([record]);
    expect(opened).toEqual(['signup']);
  });

  it('회원은 진행한다', async () => {
    const { g, opened } = gate('member');
    await expect(g.requireMember(record)).resolves.toBe(true);
    expect(opened).toEqual([]);
  });

  it('재동의 필요 회원의 쓰기는 막고 재동의 시트를 연다', async () => {
    const { g, saved, opened } = gate('member', true);
    await expect(g.requireMember(feedback)).resolves.toBe(false);
    expect(saved).toEqual([feedback]);
    expect(opened).toEqual(['reagree']);
  });

  it('서버가 재동의 필요로 거절하면 상태를 기억하고 시트를 연다', async () => {
    const { g, opened } = gate('member');
    const { ApiError } = jest.requireActual('@/api/errors');
    const error = new ApiError({ code: 'TERMS_REAGREEMENT_REQUIRED', status: 403, detail: null });
    await expect(g.handleWriteError(error, record)).resolves.toBe(true);
    expect(opened).toEqual(['reagree']);
    await expect(g.requireMember(record)).resolves.toBe(false);
  });
});
