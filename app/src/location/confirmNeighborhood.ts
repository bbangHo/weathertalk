// 판정한 동네를 쓰기 전에 사용자에게 묻는 확인 창 (ADR 0009: 서버로 가는 동네 ID는 사용자가 고르거나 확인한 값만).
import { Alert } from '@/platform/alert';

/** 확인하면 true, 아니요·창 닫기면 false. */
export function confirmNeighborhood(title: string, message: string, confirmText: string): Promise<boolean> {
  return new Promise((resolve) => {
    Alert.alert(title, message, [
      { text: '아니요', style: 'cancel', onPress: () => resolve(false) },
      { text: confirmText, onPress: () => resolve(true) },
    ]);
  });
}

/** 쓰기 직전 다시 판정한 동네가 화면의 현재 동네와 다를 때. */
export function confirmWriteNeighborhood(name: string): Promise<boolean> {
  return confirmNeighborhood(`지금 위치는 ${name}이에요`, '현재 동네가 바뀌었어요. 이 동네로 바꾸고 남길까요?', '이 동네로 남기기');
}
