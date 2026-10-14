import { useLocalSearchParams } from 'expo-router';

import { PlaceholderScreen } from '@/components/ui';

export default function PostDetailScreen() {
  const { postId } = useLocalSearchParams<{ postId: string }>();
  return (
    <PlaceholderScreen
      code="SCR-07"
      title="게시글 상세"
      description={`게시글 전문과 댓글·답글을 보고 반응해요. (게시글 ${postId ?? '-'})`}
    />
  );
}
