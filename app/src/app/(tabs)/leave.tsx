// 탭 자리만 차지하는 경로. 탭 버튼이 선택 시트를 열므로 직접 열리지 않으며, 주소로 들어오면 홈으로 보낸다.
import { Redirect } from 'expo-router';

import { routes } from '@/navigation/routes';

export default function LeavePlaceholder() {
  return <Redirect href={routes.home()} />;
}
