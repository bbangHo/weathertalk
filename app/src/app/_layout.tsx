// 루트 스택: 하단 탭 + 어느 탭에서나 여는 모달 (07 §4, 규칙 1)
import { Stack } from 'expo-router';
import * as SplashScreen from 'expo-splash-screen';
import { StatusBar } from 'expo-status-bar';
import { View } from 'react-native';

import { AuthGate } from '@/auth/AuthGate';
// 좋아요 보류 행동의 자동 적용 처리기를 앱 시작 때 등록한다(07 §3 규칙 4).
import '@/community/likes';
import { DemoBadge } from '@/demo/DemoBadge';
import { AppLifecycle } from '@/entry/AppLifecycle';
import { stackScreenOptions } from '@/navigation/stackOptions';
import { AlertHost } from '@/platform/alert';
import { useWideLayout } from '@/platform/wideLayout';
import { AppProviders } from '@/providers/AppProviders';

// 세션 복원·기기 저장값 읽기가 끝날 때까지 스플래시를 유지한다(07 §2-9). 내리는 곳은 AppLifecycle.
SplashScreen.preventAutoHideAsync().catch(() => undefined);

// 넓은 화면(PC 웹): 탭 밖 화면(첫 실행·동네 검색·남기기 폼 등)은 가운데 열로 읽기 좋은 폭만 쓴다.
const wideScreenOptions = {
  ...stackScreenOptions,
  contentStyle: { ...stackScreenOptions.contentStyle, width: '100%', maxWidth: 720, alignSelf: 'center' },
} as const;

export default function RootLayout() {
  const wide = useWideLayout();
  return (
    <AppProviders>
      <AppLifecycle />
      <AuthGate />
      <StatusBar style="dark" />
      <DemoBadge />
      <View style={{ flex: 1 }}>
        <Stack screenOptions={wide ? wideScreenOptions : stackScreenOptions}>
          <Stack.Screen name="(tabs)" options={{ headerShown: false, contentStyle: stackScreenOptions.contentStyle }} />
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
          <Stack.Screen name="preview" options={{ headerShown: false }} />
        </Stack>
      </View>
      <AlertHost />
    </AppProviders>
  );
}
