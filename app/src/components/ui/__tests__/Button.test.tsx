// 07 §5 중복 제출 방지: 요청 중 버튼 비활성
import { act, fireEvent, render, screen } from '@testing-library/react-native';

import { Button } from '../Button';

describe('Button', () => {
  it('onPress가 끝날 때까지 다시 눌러도 호출하지 않는다', async () => {
    let finish: () => void = () => {};
    const onPress = jest.fn(
      () =>
        new Promise<void>((resolve) => {
          finish = resolve;
        }),
    );
    render(<Button title="등록" onPress={onPress} testID="submit" />);

    fireEvent.press(screen.getByTestId('submit'));
    fireEvent.press(screen.getByTestId('submit'));
    expect(onPress).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId('submit')).toBeDisabled();

    await act(async () => finish());
    expect(screen.getByTestId('submit')).toBeEnabled();
    fireEvent.press(screen.getByTestId('submit'));
    expect(onPress).toHaveBeenCalledTimes(2);
  });
});
