import { PlaceholderScreen } from '@/components/ui';
import { routes } from '@/navigation/routes';

export default function InterestNeighborhoodsScreen() {
  return (
    <PlaceholderScreen
      code="SCR-02"
      title="관심 동네 관리"
      description="관심 동네를 추가·삭제하고 순서를 바꿔요."
      links={[
        { label: '동네 추가', href: routes.neighborhoodSearch() },
      ]}
    />
  );
}
