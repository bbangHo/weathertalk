// SCR-02 현재 동네 줄 '다시 찾기': 기기 안에서 판정하고, 확인한 뒤에만 현재·보는 동네를 바꾸며 회원은 동네 ID로 서버도 맞춘다(ADR 0009)
import AsyncStorage from '@react-native-async-storage/async-storage';
import { act, fireEvent, screen, waitFor } from '@testing-library/react-native';
import * as Location from 'expo-location';
import * as SecureStore from 'expo-secure-store';
import { Alert } from 'react-native';

import { sessionStore } from '@/session/sessionStore';
import { jsonResponse, tokens } from '@/test-utils/apiClient';
import { installFetch, pathOf } from '@/test-utils/globalFetch';
import { neighborhoodResponse } from '@/test-utils/homeFixtures';
import { renderWithClient } from '@/test-utils/render';

import { NeighborhoodSwitchScreen } from '../NeighborhoodSwitchScreen';
import { viewingStore } from '../viewingStore';

const mockedLocation = Location as jest.Mocked<typeof Location>;
const CURRENT_PATH = '/api/v1/members/me/current-neighborhood';
/** jest.setup의 기본 모의 좌표(37.5, 127.0)가 속한 동네 */
const BANPO2 = '1165057000';

beforeEach(async () => {
  await AsyncStorage.clear();
  (SecureStore as unknown as { __store: Map<string, string> }).__store.clear();
  sessionStore.becomeGuest();
  viewingStore.reset();
  await viewingStore.hydrate();
  mockedLocation.getForegroundPermissionsAsync.mockResolvedValue({
    status: 'granted',
    granted: true,
    canAskAgain: true,
    expires: 'never',
  } as never);
});

function serve() {
  return installFetch((call) => {
    const path = pathOf(call);
    if (path === CURRENT_PATH) {
      return jsonResponse(200, { neighborhood: neighborhoodResponse(BANPO2, '반포2동'), confirmedAt: null });
    }
    if (path === '/api/v1/members/me/interest-neighborhoods') {
      return jsonResponse(200, []);
    }
    return jsonResponse(200, neighborhoodResponse(BANPO2, '반포2동'));
  });
}

/** 확인 창에서 index번째 버튼(0: 아니요, 1: 확인)을 누른다 */
function answerAlert(index: number) {
  return jest
    .spyOn(Alert, 'alert')
    .mockImplementation((_title, _message, buttons) => buttons?.[index]?.onPress?.());
}

it('회원: 판정한 동네를 묻고, 확인하면 좌표 없이 동네 ID로만 서버 현재 동네를 바꾼다', async () => {
  await sessionStore.saveTokens(tokens('m'));
  const alert = answerAlert(1);
  const { calls } = serve();
  renderWithClient(<NeighborhoodSwitchScreen />);

  fireEvent.press(await screen.findByText('다시 찾기'));

  await waitFor(() => expect(calls.filter((call) => pathOf(call) === CURRENT_PATH)).toHaveLength(1));
  expect(alert).toHaveBeenCalledWith('지금 위치는 반포2동이에요', '이 동네로 할까요?', expect.any(Array));
  const put = calls.find((call) => pathOf(call) === CURRENT_PATH)!;
  expect(put.method).toBe('PUT');
  expect(put.body).toEqual({ neighborhoodId: BANPO2 });
  expect(viewingStore.getState()).toMatchObject({ viewingNeighborhoodId: BANPO2, current: { neighborhoodId: BANPO2 } });
  alert.mockRestore();
});

it('아니요를 누르면 현재·보는 동네를 바꾸지 않고 서버로도 보내지 않는다', async () => {
  await sessionStore.saveTokens(tokens('m'));
  const alert = answerAlert(0);
  const { calls } = serve();
  renderWithClient(<NeighborhoodSwitchScreen />);

  fireEvent.press(await screen.findByText('다시 찾기'));

  await waitFor(() => expect(alert).toHaveBeenCalledTimes(1));
  await act(async () => undefined);
  expect(viewingStore.getState()).toMatchObject({ viewingNeighborhoodId: null, current: null });
  expect(calls.filter((call) => pathOf(call) === CURRENT_PATH)).toHaveLength(0);
  expect(calls.some((call) => pathOf(call).includes(BANPO2))).toBe(false);
  alert.mockRestore();
});

it('비회원: 확인하면 기기에만 반영하고 서버 현재 동네는 부르지 않는다', async () => {
  const alert = answerAlert(1);
  const { calls } = serve();
  renderWithClient(<NeighborhoodSwitchScreen />);

  fireEvent.press(await screen.findByText('다시 찾기'));

  await waitFor(() => expect(viewingStore.getState().current?.neighborhoodId).toBe(BANPO2));
  expect(calls.filter((call) => pathOf(call) === CURRENT_PATH)).toHaveLength(0);
  alert.mockRestore();
});
