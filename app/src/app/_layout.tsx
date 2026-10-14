// 루트 스택: 하단 탭 + 어느 탭에서나 여는 모달 (07 §4, 규칙 1)
import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';

import { AppProviders } from '@/providers/AppProviders';
import { colors } from '@/theme';

export default function RootLayout() {
  return (
    <AppProviders>
      <StatusBar style="dark" />
      <Stack
        screenOptions={{
          headerTintColor: colors.textPrimary,
          contentStyle: { backgroundColor: colors.background },
        }}
      >
        <Stack.Screen name="(tabs)" options={{ headerShown: false }} />
        <Stack.Screen
          name="neighborhood-switch"
          options={{ presentation: 'formSheet', title: '동네 전환', sheetAllowedDetents: [0.5, 1] }}
        />
        <Stack.Screen name="neighborhood-search" options={{ presentation: 'modal', title: '동네 검색' }} />
        <Stack.Screen name="feeling-record" options={{ presentation: 'modal', title: '체감 기록' }} />
        <Stack.Screen name="field-report" options={{ presentation: 'modal', title: '현장 제보' }} />
        <Stack.Screen name="post-editor" options={{ presentation: 'modal', title: '글쓰기' }} />
        <Stack.Screen name="signup" options={{ presentation: 'modal', title: '가입' }} />
        <Stack.Screen name="welcome" options={{ headerShown: false, gestureEnabled: false }} />
      </Stack>
    </AppProviders>
  );
}
