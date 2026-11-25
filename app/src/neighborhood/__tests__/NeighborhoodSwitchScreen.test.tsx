// SCR-02 현재 동네 줄 '다시 찾기': 기기 안에서 판정하고, 회원은 확인을 받은 뒤에만 동네 ID로 서버 현재 동네를 바꾼다(ADR 0009)
import AsyncStorage from '@react-native-async-storage/async-storage';
import { fireEvent, screen, waitFor } from '@testing-library/react-native';
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

type AlertButtons = Parameters<typeof Alert.alert>[2];

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

it('회원: 판정한 동네를 묻고, 확인하면 좌표 없이 동네 ID로만 서버 현재 동네를 바꾼다', async () => {
  await sessionStore.saveTokens(tokens('m'));
  const alert = jest.spyOn(Alert, 'alert').mockImplementation(() => undefined);
  const { calls } = serve();
  renderWithClient(<NeighborhoodSwitchScreen />);

  fireEvent.press(await screen.findByText('다시 찾기'));

  await waitFor(() => expect(alert).toHaveBeenCalledTimes(1));
  expect(alert.mock.calls[0][0]).toBe('지금 위치는 반포2동이에요');
  expect(alert.mock.calls[0][1]).toBe('이 동네로 할까요?');
  expect(viewingStore.getState().current?.neighborhoodId).toBe(BANPO2);
  // 확인 전에는 서버로 아무것도 보내지 않는다
  expect(calls.filter((call) => pathOf(call) === CURRENT_PATH)).toHaveLength(0);

  const buttons = alert.mock.calls[0][2] as NonNullable<AlertButtons>;
  buttons.find((button) => button.style !== 'cancel')!.onPress!();

  await waitFor(() => expect(calls.filter((call) => pathOf(call) === CURRENT_PATH)).toHaveLength(1));
  const put = calls.find((call) => pathOf(call) === CURRENT_PATH)!;
  expect(put.method).toBe('PUT');
  expect(put.body).toEqual({ neighborhoodId: BANPO2 });
  alert.mockRestore();
});

it('비회원: 기기 안에서만 판정하고 묻지도, 서버로 보내지도 않는다', async () => {
  const alert = jest.spyOn(Alert, 'alert').mockImplementation(() => undefined);
  const { calls } = serve();
  renderWithClient(<NeighborhoodSwitchScreen />);

  fireEvent.press(await screen.findByText('다시 찾기'));

  await waitFor(() => expect(viewingStore.getState().current?.neighborhoodId).toBe(BANPO2));
  expect(alert).not.toHaveBeenCalled();
  expect(calls.filter((call) => pathOf(call) === CURRENT_PATH)).toHaveLength(0);
  alert.mockRestore();
});
