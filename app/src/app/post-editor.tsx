import { Stack, useLocalSearchParams } from 'expo-router';

import { PostEditorScreen } from '@/community/PostEditorScreen';

export default function PostEditorRoute() {
  const { postId, feelingRecordId } = useLocalSearchParams<{ postId?: string; feelingRecordId?: string }>();
  return (
    <>
      <Stack.Screen options={{ title: postId ? '글 고치기' : '글쓰기' }} />
      <PostEditorScreen postId={postId || undefined} feelingRecordId={feelingRecordId || undefined} />
    </>
  );
}
