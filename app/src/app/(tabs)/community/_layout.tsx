import { Stack } from 'expo-router';

export default function CommunityStackLayout() {
  return (
    <Stack>
      <Stack.Screen name="index" options={{ title: '커뮤니티' }} />
      <Stack.Screen name="[postId]" options={{ title: '게시글' }} />
    </Stack>
  );
}
