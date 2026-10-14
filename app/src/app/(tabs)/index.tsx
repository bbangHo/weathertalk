import { PlaceholderScreen } from '@/components/ui';
import { routes } from '@/navigation/routes';

export default function HomeScreen() {
  return (
    <PlaceholderScreen
      code="SCR-01"
      title="홈"
      description="보는 동네의 지금 날씨와 민감도별 체감 3그룹, 체감 피드백, 옷 추천, 예보, 동네 인기글을 섹션으로 보여줘요."
      links={[
        { label: '동네 전환', href: routes.neighborhoodSwitch() },
        { label: '첫 실행 안내', href: routes.welcome() },
      ]}
    />
  );
}
