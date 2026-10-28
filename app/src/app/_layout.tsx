// 루트 스택: 하단 탭 + 어느 탭에서나 여는 모달 (07 §4, 규칙 1)
import { Stack } from 'expo-router';
import * as SplashScreen from 'expo-splash-screen';
import { StatusBar } from 'expo-status-bar';

import { AuthGate } from '@/auth/AuthGate';
// 좋아요 보류 행동의 자동 적용 처리기를 앱 시작 때 등록한다(07 §3 규칙 4).
import '@/community/likes';
import { AppLifecycle } from '@/entry/AppLifecycle';
import { stackScreenOptions } from '@/navigation/stackOptions';
import { AppProviders } from '@/providers/AppProviders';

// 세션 복원·기기 저장값 읽기가 끝날 때까지 스플래시를 유지한다(07 §2-9). 내리는 곳은 AppLifecycle.
SplashScreen.preventAutoHideAsync().catch(() => undefined);

export default function RootLayout() {
  return (
    <AppProviders>
      <AppLifecycle />
      <AuthGate />
      <StatusBar style="dark" />
      <Stack screenOptions={stackScreenOptions}>
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
