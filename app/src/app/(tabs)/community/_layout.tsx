import { Stack } from 'expo-router';

import { stackScreenOptions } from '@/navigation/stackOptions';

export default function CommunityStackLayout() {
  return (
    <Stack screenOptions={stackScreenOptions}>
      <Stack.Screen name="index" options={{ title: '커뮤니티' }} />
      <Stack.Screen name="[postId]" options={{ title: '게시글' }} />
    </Stack>
  );
}
