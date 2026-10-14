import { PlaceholderScreen } from '@/components/ui';
import { routes } from '@/navigation/routes';

export default function MyPageScreen() {
  return (
    <PlaceholderScreen
      code="SCR-09"
      title="마이"
      description="회원 정보와 설정, 계정 관리. 비회원은 가입 카드와 기기 설정만 보여줘요."
      links={[
        { label: '알림 구독 설정', href: routes.notificationSettings() },
        { label: '관심 동네 관리', href: routes.interestNeighborhoods() },
        { label: '내가 쓴 글', href: routes.myPosts() },
        { label: '내 체감 기록', href: routes.myFeelingRecords() },
        { label: '가입', href: routes.signup() },
      ]}
    />
  );
}
