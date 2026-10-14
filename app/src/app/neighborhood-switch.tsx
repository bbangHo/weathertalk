import { PlaceholderScreen } from '@/components/ui';
import { routes } from '@/navigation/routes';

export default function NeighborhoodSwitchSheet() {
  return (
    <PlaceholderScreen
      code="SCR-02"
      title="동네 전환"
      description="현재 동네와 관심 동네 중에서 보는 동네를 골라요."
      links={[
        { label: '동네 추가', href: routes.neighborhoodSearch() },
      ]}
    />
  );
}
