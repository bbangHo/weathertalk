// 실제 src/app 라우트 구조로 루트 레이아웃이 그려지고 주요 경로가 열리는지 확인한다.
import AsyncStorage from '@react-native-async-storage/async-storage';
import { fireEvent, waitFor } from '@testing-library/react-native';
import { renderRouter, screen, testRouter } from 'expo-router/testing-library';

import { viewingStore } from '@/neighborhood/viewingStore';
import { routes } from '@/navigation/routes';
import { generalStore } from '@/storage';
import { problem } from '@/test-utils/apiClient';
import { installFetch } from '@/test-utils/globalFetch';

import type { Href } from 'expo-router';

function pathOf(href: Href): string {
  if (typeof href !== 'string') {
    throw new Error('이 테스트는 문자열 경로만 쓴다');
  }
  return href;
}

// renderRouter의 경로 문자열은 테스트 파일이 아니라 실행 위치(app 루트) 기준이다.
const APP_DIR = 'src/app';

jest.setTimeout(30000);

// 첫 실행을 마친 기기, 서버는 응답하지 않는 상태로 시작한다(홈은 '동네를 골라 주세요').
beforeEach(async () => {
  await AsyncStorage.clear();
  viewingStore.reset();
  await generalStore.set('onboardingCompleted', true);
  installFetch(() => problem(503, 'TEMPORARILY_UNAVAILABLE'));
});

describe('라우트 골격', () => {
  it('홈 탭으로 시작하고 하단 탭과 남기기 버튼이 보인다', async () => {
    await renderRouter(APP_DIR);

    expect(screen).toHavePathname('/');
    expect(await screen.findByText('동네를 골라 주세요')).toBeTruthy();
    expect(screen.getByTestId('leave-tab-button')).toBeTruthy();
    expect(screen.getAllByText('커뮤니티').length).toBeGreaterThan(0);
    expect(screen.getAllByText('마이').length).toBeGreaterThan(0);
  });

  it('남기기는 화면 이동 없이 선택 시트를 열고, 고르면 작성 모달로 간다', async () => {
    await renderRouter(APP_DIR);
    await screen.findByText('동네를 골라 주세요');

    fireEvent.press(screen.getByTestId('leave-tab-button'));
    expect(screen).toHavePathname('/');
    expect(await screen.findByTestId('leave-choice-sheet')).toBeTruthy();

    fireEvent.press(screen.getByText('현장 제보'));
    await waitFor(() => expect(screen).toHavePathname('/field-report'));
  });

  it('탭 안 화면과 루트 모달을 경로로 연다', async () => {
    await renderRouter(APP_DIR);

    await testRouter.push(pathOf(routes.postDetail('p1')));
    expect(screen).toHavePathname('/community/p1');
    // A3에서 자리표시 화면이 실제 게시글 상세로 바뀌었다(서버가 응답하지 않아 오류 + 다시 시도).
    // 5xx는 한 번 더 시도한 뒤 오류를 보여준다.
    expect(await screen.findByText('글을 불러오지 못했어요', {}, { timeout: 5000 })).toBeTruthy();

    await testRouter.push(pathOf(routes.feelingRecord()));
    expect(screen).toHavePathname('/feeling-record');
    // A2에서 자리표시 화면이 실제 체감 기록 화면으로 바뀌었다.
    expect(await screen.findByText('기록은 지금 있는 동네(현재 동네)에 남아요')).toBeTruthy();

    await testRouter.push(pathOf(routes.myFeelingRecords()));
    expect(screen).toHavePathname('/my/feeling-records');
  });
});
