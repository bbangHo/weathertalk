import { PlaceholderScreen } from '@/components/ui';
import { routes } from '@/navigation/routes';

export default function WelcomeScreen() {
  return (
    <PlaceholderScreen
      code="SCR-12"
      title="첫 실행 안내"
      description="서비스 소개와 위치를 동네 판정에만 쓰고 저장하지 않는다는 안내. 위치 사용하기 / 나중에(동네 직접 고르기)."
      links={[
        { label: '위치 사용하기(자리표시)', href: routes.home() },
        { label: '나중에(동네 직접 고르기)', href: routes.neighborhoodSearch({ fromOnboarding: true }) },
      ]}
    />
  );
}
