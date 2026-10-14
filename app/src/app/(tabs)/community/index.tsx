import { PlaceholderScreen } from '@/components/ui';
import { routes } from '@/navigation/routes';

export default function CommunityFeedScreen() {
  return (
    <PlaceholderScreen
      code="SCR-06"
      title="커뮤니티"
      description="보는 동네의 게시글을 최신순으로 보여줘요. 페이지 단위로 이어 불러와요."
      links={[
        { label: '게시글 상세(예시)', href: routes.postDetail('sample') },
        { label: '동네 전환', href: routes.neighborhoodSwitch() },
      ]}
    />
  );
}
