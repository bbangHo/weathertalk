// SCR-09 마이페이지(회원/비회원), 닉네임 거부 사유, 로그아웃 확인 문구, 탈퇴 안내·실패 사유, SCR-13·14 빈 상태
import AsyncStorage from '@react-native-async-storage/async-storage';
import { act, fireEvent, screen, waitFor } from '@testing-library/react-native';
import * as SecureStore from 'expo-secure-store';
import { Alert } from 'react-native';

import type { MyProfile } from '@/auth/api';
import { signupSheet } from '@/auth/signupSheet';
import { termsStatus } from '@/auth/termsStatus';
import { viewingStore } from '@/neighborhood/viewingStore';
import { pendingActionStore } from '@/pending/pendingAction';
import { sessionStore } from '@/session/sessionStore';
import { generalStore } from '@/storage';
import { jsonResponse, problem, tokens, type FakeCall } from '@/test-utils/apiClient';
import { page, post } from '@/test-utils/communityFixtures';
import { installFetch, pathOf } from '@/test-utils/globalFetch';
import { renderWithClient } from '@/test-utils/render';
import { mockRouter, resetRouterMock } from '@/test-utils/routerMock';

import type { MyFeelingRecords } from '../api';
import { MyFeelingRecordsScreen, MY_RECORDS_EMPTY } from '../MyFeelingRecordsScreen';
import { MyPageScreen } from '../MyPageScreen';
import { MY_POSTS_EMPTY, MyPostsScreen } from '../MyPostsScreen';
import { LOGOUT_CONFIRM, type SignOutDeps, WITHDRAW_DONE } from '../signOut';

// 팩토리 안에서는 import를 쓸 수 없다(호이스팅).
// eslint-disable-next-line @typescript-eslint/no-require-imports
jest.mock('expo-router', () => require('@/test-utils/routerMock').expoRouterMock());

const PROFILE: MyProfile = {
  memberId: 'm1',
  nickname: '맑은하늘',
  temperatureSensitivity: 'NORMAL',
  email: null,
  providers: ['KAKAO', 'GOOGLE'],
  nicknameChangeAvailableAt: null,
  termsReagreementRequired: false,
  termsToReagree: [],
};

const RECORDS: MyFeelingRecords = {
  records: [
    {
      id: { value: 'rec-1' },
      recordedAt: '2026-10-15T00:00:00Z',
      neighborhoodId: { value: 'N1' },
      neighborhoodName: '강남구 역삼1동',
      temperatureTag: 'COLD',
      humidityTag: 'DRY',
      windTag: null,
      source: 'FEEDBACK',
      temperatureSensitivity: 'NORMAL',
    },
  ],
  pattern: {
    recordCount: 1,
    temperatureDistribution: { COLD: 1 },
    tendency: 'INSUFFICIENT_RECORDS',
    tendencyPhrase: '기록이 조금 더 모이면 알려 드릴게요',
    averageDifference: null,
    tendencySampleCount: 0,
  },
};

type AlertButton = { text?: string; onPress?: () => void | Promise<void> };
let alertSpy: jest.SpyInstance;

function lastAlert() {
  const call = alertSpy.mock.calls[alertSpy.mock.calls.length - 1];
  return { title: call[0] as string, message: call[1] as string, buttons: (call[2] ?? []) as AlertButton[] };
}

async function pressAlert(text: string) {
  await act(async () => {
    await lastAlert().buttons.find((button) => button.text === text)!.onPress!();
  });
}

/** 회원 마이페이지 서버 */
function memberServer(overrides: (call: FakeCall) => Response | null = () => null) {
  return installFetch((call) => {
    const custom = overrides(call);
    if (custom) {
      return custom;
    }
    switch (pathOf(call)) {
      case '/api/v1/members/me':
        return jsonResponse(200, PROFILE);
      case '/api/v1/members/me/calibration-offer':
        return jsonResponse(200, { offer: null });
      case '/api/v1/members/me/posts':
        return jsonResponse(200, page([post('p1', { body: '내 글' })]));
      case '/api/v1/members/me/feeling-records':
        return jsonResponse(200, RECORDS);
      default:
        return problem(500, 'X');
    }
  });
}

beforeEach(async () => {
  await AsyncStorage.clear();
  (SecureStore as unknown as { __store: Map<string, string> }).__store.clear();
  sessionStore.becomeGuest();
  termsStatus.reset();
  signupSheet.setMounted(false);
  viewingStore.reset();
  resetRouterMock();
  alertSpy = jest.spyOn(Alert, 'alert').mockImplementation(() => undefined);
});

afterEach(() => alertSpy.mockRestore());

describe('비회원 마이페이지', () => {
  it('가입 카드와 기기 설정만 보여주고, 가입은 보류 행동 없이 시트를 연다', async () => {
    const { calls } = installFetch(() => problem(500, 'X'));
    renderWithClient(<MyPageScreen />);

    expect(screen.getByTestId('guest-signup-card')).toBeTruthy();
    expect(screen.getByTestId('guest-sensitivity')).toBeTruthy();
    expect(screen.getByText('관심 동네 관리')).toBeTruthy();
    expect(screen.getByText('알림 구독 설정')).toBeTruthy();
    expect(screen.getByTestId('app-version')).toBeTruthy();
    expect(screen.queryByTestId('profile-card')).toBeNull();
    expect(screen.queryByTestId('logout')).toBeNull();
    expect(screen.queryByTestId('my-posts-preview')).toBeNull();

    fireEvent.press(screen.getByTestId('guest-signup'));
    expect(mockRouter.push).toHaveBeenCalledWith('/signup');
    expect(await pendingActionStore.load()).toBeNull();
    expect(calls).toHaveLength(0);
  });

  it('기기 민감도를 고르면 기기에 저장한다', async () => {
    installFetch(() => problem(500, 'X'));
    renderWithClient(<MyPageScreen />);
    fireEvent.press(screen.getByTestId('sensitivity-COLD_SENSITIVE'));
    await waitFor(async () => expect(await generalStore.get('guestTemperatureSensitivity')).toBe('COLD_SENSITIVE'));
  });
});

describe('회원 마이페이지', () => {
  beforeEach(async () => {
    await sessionStore.saveTokens(tokens('m'));
  });

  it('닉네임·민감도·로그인 계정·내가 쓴 글·내 체감 기록(패턴)을 보여준다', async () => {
    memberServer();
    renderWithClient(<MyPageScreen />);

    expect(await screen.findByTestId('nickname')).toHaveTextContent('맑은하늘');
    expect(screen.getByTestId('sensitivity-NORMAL').props.accessibilityState.selected).toBe(true);
    expect(screen.getByText('바꿔도 이전 체감 기록은 그대로 남아요')).toBeTruthy();
    expect(screen.getByTestId('account-KAKAO')).toBeTruthy();
    expect(screen.getByTestId('account-GOOGLE')).toBeTruthy();
    expect(await screen.findByText('내 글')).toBeTruthy();
    expect(await screen.findByText('기록이 조금 더 모이면 알려 드릴게요')).toBeTruthy();
    expect(screen.getByTestId('feeling-record-rec-1')).toBeTruthy();
    expect(screen.queryByTestId('guest-signup-card')).toBeNull();

    fireEvent.press(screen.getByTestId('my-posts-more'));
    expect(mockRouter.push).toHaveBeenCalledWith('/my/posts');
    fireEvent.press(screen.getByTestId('my-feeling-more'));
    expect(mockRouter.push).toHaveBeenCalledWith('/my/feeling-records');
  });

  it('회원 정보 조회가 실패하면 기본값 없이 오류와 다시 시도를 보여준다', async () => {
    let fail = true;
    memberServer((call) => (pathOf(call) === '/api/v1/members/me' && fail ? problem(500, 'X') : null));
    renderWithClient(<MyPageScreen />);

    expect(await screen.findByText('회원 정보를 불러오지 못했어요')).toBeTruthy();
    expect(screen.queryByTestId('nickname')).toBeNull();
    fail = false;
    fireEvent.press(screen.getByText('다시 시도'));
    expect(await screen.findByTestId('nickname')).toHaveTextContent('맑은하늘');
  });

  it('닉네임 중복·변경 간격 거부는 서버 사유를 그대로, 간격이면 다음 변경 날짜를 함께 보여준다', async () => {
    let response: Response = problem(409, 'NICKNAME_TAKEN', { detail: '이미 사용 중인 닉네임입니다.' });
    const { calls } = memberServer((call) => (pathOf(call) === '/api/v1/members/me/nickname' ? response : null));
    renderWithClient(<MyPageScreen />);
    await screen.findByTestId('nickname');

    fireEvent.press(screen.getByTestId('nickname-edit'));
    fireEvent.changeText(screen.getByTestId('nickname-input'), '흐린하늘');
    fireEvent.press(screen.getByTestId('nickname-save'));
    expect(await screen.findByTestId('nickname-message')).toHaveTextContent('이미 사용 중인 닉네임입니다.');
    expect(calls.find((call) => pathOf(call) === '/api/v1/members/me/nickname')!.body).toEqual({ nickname: '흐린하늘' });

    response = problem(409, 'NICKNAME_CHANGE_TOO_SOON', {
      detail: '닉네임은 아직 바꿀 수 없습니다.',
      nextChangeAvailableAt: '2026-11-14T03:00:00Z',
    });
    fireEvent.press(screen.getByTestId('nickname-save'));
    await waitFor(() =>
      expect(screen.getByTestId('nickname-message')).toHaveTextContent(
        '닉네임은 아직 바꿀 수 없습니다. 2026년 11월 14일부터 바꿀 수 있어요.',
      ),
    );

    response = jsonResponse(200, { nickname: '흐린하늘', nextChangeAvailableAt: '2026-11-14T03:00:00Z' });
    fireEvent.press(screen.getByTestId('nickname-save'));
    expect(await screen.findByText('흐린하늘')).toBeTruthy();
    expect(screen.getByTestId('nickname-message')).toHaveTextContent(/2026년 11월 14일부터/);
  });

  it('닉네임 길이가 맞지 않으면 저장할 수 없다', async () => {
    memberServer();
    renderWithClient(<MyPageScreen />);
    await screen.findByTestId('nickname');
    fireEvent.press(screen.getByTestId('nickname-edit'));
    fireEvent.changeText(screen.getByTestId('nickname-input'), '가');
    expect(screen.getByTestId('nickname-save').props.accessibilityState.disabled).toBe(true);
    fireEvent.changeText(screen.getByTestId('nickname-input'), '가'.repeat(11));
    expect(screen.getByTestId('nickname-save').props.accessibilityState.disabled).toBe(true);
  });

  it('마지막 로그인 계정 해제 거부는 서버 사유를 그대로 보여준다', async () => {
    memberServer((call) =>
      call.method === 'DELETE'
        ? problem(422, 'LAST_LOGIN_ACCOUNT', { detail: '마지막 로그인 계정은 해제할 수 없습니다. 탈퇴를 이용해 주세요.' })
        : null,
    );
    renderWithClient(<MyPageScreen />);
    await screen.findByTestId('nickname');
    fireEvent.press(screen.getByTestId('unlink-KAKAO'));
    await pressAlert('해제');
    expect(await screen.findByTestId('accounts-error')).toHaveTextContent(/마지막 로그인 계정은 해제할 수 없습니다/);
  });

  it('로그아웃은 알림 안내와 함께 확인받고 정리 순서를 실행한다', async () => {
    memberServer();
    const performed = jest.fn(async () => undefined);
    const deps = { serverLogout: performed } as unknown as SignOutDeps;
    const signOutDeps: SignOutDeps = {
      ...deps,
      serverWithdraw: jest.fn(),
      clearMemberData: jest.fn(async () => undefined),
      becomeGuest: jest.fn(),
      rehydrateViewing: jest.fn(async () => undefined),
      clearHomeMemory: jest.fn(),
      syncDevice: jest.fn(async () => undefined),
      goHome: jest.fn(),
    };
    renderWithClient(<MyPageScreen signOutDeps={signOutDeps} />);
    await screen.findByTestId('nickname');

    fireEvent.press(screen.getByTestId('logout'));
    expect(lastAlert().message).toBe(LOGOUT_CONFIRM);
    expect(performed).not.toHaveBeenCalled();
    await pressAlert('로그아웃');
    expect(performed).toHaveBeenCalled();
    expect(signOutDeps.becomeGuest).toHaveBeenCalled();
    expect(signOutDeps.goHome).toHaveBeenCalled();
  });

  it('탈퇴는 결과를 안내해 확인받고, 성공하면 알림 안내를 보여주며 실패하면 서버 사유를 보여준다', async () => {
    memberServer();
    let reject = true;
    const signOutDeps: SignOutDeps = {
      serverLogout: jest.fn(),
      serverWithdraw: jest.fn(async () => {
        if (reject) {
          const { ApiError } = jest.requireActual('@/api/errors');
          throw new ApiError({ code: 'TEMPORARILY_UNAVAILABLE', status: 503, detail: '잠시 후 다시 시도해 주세요.' });
        }
      }),
      clearMemberData: jest.fn(async () => undefined),
      becomeGuest: jest.fn(),
      rehydrateViewing: jest.fn(async () => undefined),
      clearHomeMemory: jest.fn(),
      syncDevice: jest.fn(async () => undefined),
      goHome: jest.fn(),
    };
    renderWithClient(<MyPageScreen signOutDeps={signOutDeps} />);
    await screen.findByTestId('nickname');

    fireEvent.press(screen.getByTestId('withdraw'));
    expect(lastAlert().message).toContain('글·댓글·좋아요는 지워지고');
    expect(lastAlert().message).toContain('체감 기록은 누가 남겼는지 알 수 없는 기록으로 남아요');
    await pressAlert('탈퇴');
    expect(await screen.findByTestId('account-error')).toHaveTextContent('잠시 후 다시 시도해 주세요.');
    expect(signOutDeps.clearMemberData).not.toHaveBeenCalled();

    reject = false;
    fireEvent.press(screen.getByTestId('withdraw'));
    await pressAlert('탈퇴');
    expect(signOutDeps.clearMemberData).toHaveBeenCalled();
    expect(lastAlert().message).toBe(WITHDRAW_DONE);
  });
});

describe('SCR-13·14', () => {
  beforeEach(async () => {
    await sessionStore.saveTokens(tokens('m'));
  });

  it('내가 쓴 글이 없으면 빈 상태를 보여준다', async () => {
    memberServer((call) => (pathOf(call) === '/api/v1/members/me/posts' ? jsonResponse(200, page([])) : null));
    renderWithClient(<MyPostsScreen />);
    expect(await screen.findByText(MY_POSTS_EMPTY)).toBeTruthy();
  });

  it('내가 쓴 글 카드는 동네와 함께 보이고 상세로 간다', async () => {
    memberServer();
    renderWithClient(<MyPostsScreen />);
    expect(await screen.findByText('내 글')).toBeTruthy();
    fireEvent.press(screen.getByTestId('my-post-p1'));
    expect(mockRouter.push).toHaveBeenCalledWith('/community/p1');
  });

  it('내 체감 기록이 없으면 빈 상태 + (현재 동네를 알면) 기록 버튼', async () => {
    const empty = { records: [], pattern: { ...RECORDS.pattern, recordCount: 0, temperatureDistribution: {} } };
    memberServer((call) => (pathOf(call) === '/api/v1/members/me/feeling-records' ? jsonResponse(200, empty) : null));
    await generalStore.set('currentNeighborhood', { neighborhoodId: 'N1', resolvedAt: new Date().toISOString() });
    await viewingStore.hydrate();
    renderWithClient(<MyFeelingRecordsScreen />);

    expect(await screen.findByText(MY_RECORDS_EMPTY)).toBeTruthy();
    fireEvent.press(screen.getByTestId('records-write'));
    expect(mockRouter.push).toHaveBeenCalledWith('/feeling-record');
  });

  it('현재 동네를 모르면 빈 상태에 기록 버튼이 없다', async () => {
    const empty = { records: [], pattern: { ...RECORDS.pattern, recordCount: 0, temperatureDistribution: {} } };
    memberServer((call) => (pathOf(call) === '/api/v1/members/me/feeling-records' ? jsonResponse(200, empty) : null));
    renderWithClient(<MyFeelingRecordsScreen />);
    expect(await screen.findByText(MY_RECORDS_EMPTY)).toBeTruthy();
    expect(screen.queryByTestId('records-write')).toBeNull();
  });

  it('내 체감 기록은 시각·동네·태그·출처를 보여준다', async () => {
    memberServer();
    renderWithClient(<MyFeelingRecordsScreen />);
    const row = await screen.findByTestId('feeling-record-rec-1');
    expect(row).toHaveTextContent(/강남구 역삼1동/);
    expect(row).toHaveTextContent(/피드백/);
    expect(row).toHaveTextContent(/추움 · 건조해요/);
  });
});
