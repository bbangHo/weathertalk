// 실제 src/app 라우트 구조로 루트 레이아웃이 그려지고 주요 경로가 열리는지 확인한다.
import { fireEvent, waitFor } from '@testing-library/react-native';
import { renderRouter, screen, testRouter } from 'expo-router/testing-library';

import { routes } from '@/navigation/routes';

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

describe('라우트 골격', () => {
  it('홈 탭으로 시작하고 하단 탭과 남기기 버튼이 보인다', async () => {
    await renderRouter(APP_DIR);

    expect(screen).toHavePathname('/');
    expect(await screen.findByText('SCR-01')).toBeTruthy();
    expect(screen.getByTestId('leave-tab-button')).toBeTruthy();
    expect(screen.getAllByText('커뮤니티').length).toBeGreaterThan(0);
    expect(screen.getAllByText('마이').length).toBeGreaterThan(0);
  });

  it('남기기는 화면 이동 없이 선택 시트를 열고, 고르면 작성 모달로 간다', async () => {
    await renderRouter(APP_DIR);

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
    expect(await screen.findByText('SCR-07')).toBeTruthy();

    await testRouter.push(pathOf(routes.feelingRecord()));
    expect(screen).toHavePathname('/feeling-record');
    expect(await screen.findByText('SCR-04')).toBeTruthy();

    await testRouter.push(pathOf(routes.myFeelingRecords()));
    expect(screen).toHavePathname('/my/feeling-records');
  });
});
