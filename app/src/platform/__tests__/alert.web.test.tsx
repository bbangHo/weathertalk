// 웹(앱인토스 미니앱) 확인 창: react-native-web의 빈 Alert.alert 대신 앱 안 창으로 그리고, 누른 버튼의 처리를 부른다.
import { act, fireEvent, render } from '@testing-library/react-native';

import { Alert, AlertHost } from '../alert.web';

describe('웹 확인 창', () => {
  it('버튼 셋을 그리고 누른 버튼만 실행한 뒤 닫는다', () => {
    const discard = jest.fn();
    const keep = jest.fn();
    const view = render(<AlertHost />);
    expect(view.queryByTestId('app-alert')).toBeNull();

    act(() => {
      Alert.alert('초안을 남길까요?', '다음에 이어서 쓸 수 있어요.', [
        { text: '계속 쓰기', style: 'cancel' },
        { text: '버리기', style: 'destructive', onPress: discard },
        { text: '남기기', onPress: keep },
      ]);
    });
    expect(view.getByText('초안을 남길까요?')).toBeTruthy();
    expect(view.getByText('남기기')).toBeTruthy();

    fireEvent.press(view.getByText('버리기'));
    expect(discard).toHaveBeenCalledTimes(1);
    expect(keep).not.toHaveBeenCalled();
    expect(view.queryByTestId('app-alert')).toBeNull();
    view.unmount();
  });

  it('버튼이 없으면 확인 하나, 여러 개가 오면 차례로 보여 준다', () => {
    const view = render(<AlertHost />);
    act(() => {
      Alert.alert('탈퇴했어요');
      Alert.alert('두 번째');
    });
    fireEvent.press(view.getByText('확인'));
    expect(view.getByText('두 번째')).toBeTruthy();
    fireEvent.press(view.getByText('확인'));
    expect(view.queryByTestId('app-alert')).toBeNull();
    view.unmount();
  });
});
