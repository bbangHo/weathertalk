import { useLocalSearchParams } from 'expo-router';

import { PostDetailScreen } from '@/community/PostDetailScreen';

export default function PostDetailRoute() {
  const { postId, focus } = useLocalSearchParams<{ postId: string; focus?: string }>();
  return <PostDetailScreen key={postId} postId={postId ?? ''} focus={focus === 'comments' ? 'comments' : undefined} />;
}
