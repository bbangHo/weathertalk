// 자동 판정 제안(ADR 0009): 제안만으로는 보는 동네·현재 동네·서버가 바뀌지 않고, "볼게요"를 누른 뒤에만 바뀌고 요청한다
import AsyncStorage from '@react-native-async-storage/async-storage';
import { fireEvent, screen, waitFor } from '@testing-library/react-native';
import * as SecureStore from 'expo-secure-store';

import { sessionStore } from '@/session/sessionStore';
import { generalStore } from '@/storage';
import { jsonResponse, tokens } from '@/test-utils/apiClient';
import { installFetch, pathOf } from '@/test-utils/globalFetch';
import { neighborhoodResponse } from '@/test-utils/homeFixtures';
import { renderWithClient } from '@/test-utils/render';
import { mockRouter, resetRouterMock } from '@/test-utils/routerMock';

import { NeighborhoodSuggestion } from '../NeighborhoodSuggestion';
import type { NeighborhoodSummary } from '../types';
import { viewingStore } from '../viewingStore';

// eslint-disable-next-line @typescript-eslint/no-require-imports
jest.mock('expo-router', () => require('@/test-utils/routerMock').expoRouterMock());

const BANPO2: NeighborhoodSummary = {
  id: '1165057000',
  sidoName: '서울특별시',
  sigunguName: '서초구',
  eupmyeondongName: '반포2동',
  fullName: '서울특별시 서초구 반포2동',
  shortName: '서초구 반포2동',
};
const CURRENT_PATH = '/api/v1/members/me/current-neighborhood';

beforeEach(async () => {
  await AsyncStorage.clear();
  (SecureStore as unknown as { __store: Map<string, string> }).__store.clear();
  resetRouterMock();
  sessionStore.becomeGuest();
  viewingStore.reset();
  await generalStore.set('currentNeighborhood', { neighborhoodId: 'C1', resolvedAt: '2026-10-14T00:00:00Z' });
  await generalStore.set('lastViewedNeighborhood', { neighborhoodId: 'C1' });
  await viewingStore.hydrate();
});

function serve() {
  return installFetch(() => jsonResponse(200, { neighborhood: neighborhoodResponse(BANPO2.id, '반포2동'), confirmedAt: null }));
}

it('제안이 없으면 아무것도 그리지 않는다', () => {
  renderWithClient(<NeighborhoodSuggestion />);
  expect(screen.queryByTestId('neighborhood-suggestion')).toBeNull();
});

it('제안만으로는 아무것도 바뀌지 않고 요청도 없다. 닫으면 판정 결과를 버린다', async () => {
  const { calls } = serve();
  viewingStore.suggest(BANPO2);
  renderWithClient(<NeighborhoodSuggestion />);

  expect(await screen.findByText('지금 위치는 반포2동이에요. 이 동네 날씨 볼까요?')).toBeTruthy();
  expect(viewingStore.getState()).toMatchObject({ viewingNeighborhoodId: 'C1', current: { neighborhoodId: 'C1' } });

  fireEvent.press(screen.getByTestId('suggestion-dismiss'));

  await waitFor(() => expect(screen.queryByTestId('neighborhood-suggestion')).toBeNull());
  expect(viewingStore.getState()).toMatchObject({ suggestion: null, viewingNeighborhoodId: 'C1' });
  expect(calls).toHaveLength(0);
});

it('회원이 "볼게요"를 누르면 그때 보는·현재 동네를 바꾸고 동네 ID로 서버 현재 동네를 맞춘 뒤 홈으로 간다', async () => {
  await sessionStore.saveTokens(tokens('m'));
  const { calls } = serve();
  viewingStore.suggest(BANPO2);
  renderWithClient(<NeighborhoodSuggestion />);

  fireEvent.press(await screen.findByTestId('suggestion-accept'));

  await waitFor(() => expect(mockRouter.navigate).toHaveBeenCalledWith('/'));
  expect(viewingStore.getState()).toMatchObject({
    viewingNeighborhoodId: BANPO2.id,
    current: { neighborhoodId: BANPO2.id },
    suggestion: null,
  });
  const puts = calls.filter((call) => pathOf(call) === CURRENT_PATH);
  expect(puts).toHaveLength(1);
  expect(puts[0].body).toEqual({ neighborhoodId: BANPO2.id });
});
