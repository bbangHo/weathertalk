import { Stack } from 'expo-router';

import { stackScreenOptions } from '@/navigation/stackOptions';

export default function MyStackLayout() {
  return (
    <Stack screenOptions={stackScreenOptions}>
      <Stack.Screen name="index" options={{ title: '마이' }} />
      <Stack.Screen name="notifications" options={{ title: '알림 구독 설정' }} />
      <Stack.Screen name="interest-neighborhoods" options={{ title: '관심 동네 관리' }} />
      <Stack.Screen name="posts" options={{ title: '내가 쓴 글' }} />
      <Stack.Screen name="feeling-records" options={{ title: '내 체감 기록' }} />
    </Stack>
  );
}
