// 웹(앱인토스 미니앱 포함) 확인 창. react-native-web의 Alert.alert는 아무것도 하지 않아(빈 구현) 로그아웃·삭제 확인이 막힌다.
// 같은 호출 모양(Alert.alert(제목, 내용, 버튼들))으로 받아 루트의 AlertHost가 앱 안 창으로 그린다. 버튼이 셋이어도 된다.
import { useEffect, useState } from 'react';
import { Modal, Pressable, StyleSheet, View } from 'react-native';

import { AppText } from '@/components/ui';
import { colors, radii, spacing } from '@/theme';

type AlertButton = { text?: string; style?: 'default' | 'cancel' | 'destructive'; onPress?: () => void };
type AlertRequest = { title: string; message?: string; buttons: AlertButton[] };

const queue: AlertRequest[] = [];
const listeners = new Set<() => void>();

function notify() {
  listeners.forEach((listener) => listener());
}

export const Alert = {
  alert(title: string, message?: string, buttons?: AlertButton[]) {
    queue.push({ title, message, buttons: buttons && buttons.length > 0 ? buttons : [{ text: '확인' }] });
    notify();
  },
};

export function AlertHost() {
  const [current, setCurrent] = useState<AlertRequest | null>(queue[0] ?? null);
  useEffect(() => {
    const listener = () => setCurrent(queue[0] ?? null);
    listeners.add(listener);
    listener();
    return () => {
      listeners.delete(listener);
    };
  }, []);

  const close = (button?: AlertButton) => {
    queue.shift();
    notify();
    button?.onPress?.();
  };

  if (!current) {
    return null;
  }
  const cancel = current.buttons.find((b) => b.style === 'cancel');
  return (
    <Modal transparent visible animationType="fade" onRequestClose={() => close(cancel)}>
      <View style={styles.backdrop}>
        <View style={styles.card} accessibilityRole="alert" testID="app-alert">
          <AppText variant="heading">{current.title}</AppText>
          {current.message ? <AppText tone="secondary">{current.message}</AppText> : null}
          <View style={styles.buttons}>
            {current.buttons.map((button, index) => (
              <Pressable
                key={`${button.text}-${index}`}
                accessibilityRole="button"
                onPress={() => close(button)}
                style={styles.button}
                testID={`app-alert-button-${index}`}
              >
                <AppText
                  variant="button"
                  style={{
                    color:
                      button.style === 'destructive'
                        ? colors.danger
                        : button.style === 'cancel'
                          ? colors.textSecondary
                          : colors.primary,
                  }}
                >
                  {button.text ?? '확인'}
                </AppText>
              </Pressable>
            ))}
          </View>
        </View>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  backdrop: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.4)',
    alignItems: 'center',
    justifyContent: 'center',
    padding: spacing.xl,
  },
  card: {
    width: '100%',
    maxWidth: 360,
    backgroundColor: colors.surface,
    borderRadius: radii.lg,
    padding: spacing.card,
    gap: spacing.sm,
  },
  buttons: { flexDirection: 'row', justifyContent: 'flex-end', flexWrap: 'wrap', gap: spacing.sm, marginTop: spacing.sm },
  button: { paddingVertical: spacing.sm, paddingHorizontal: spacing.md },
});
