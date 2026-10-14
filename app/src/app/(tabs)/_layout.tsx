// 하단 탭: 홈 / 커뮤니티 / [남기기] / 마이 (07 §4)
import { Tabs } from 'expo-router';

import { LeaveTabButton } from '@/components/navigation/LeaveTabButton';
import { colors } from '@/theme';

export default function TabsLayout() {
  return (
    <Tabs
      screenOptions={{
        tabBarActiveTintColor: colors.primary,
        tabBarInactiveTintColor: colors.textSecondary,
        sceneStyle: { backgroundColor: colors.background },
      }}
    >
      {/* 홈은 동네 이름 헤더를 직접 그린다(SCR-01 헤더). */}
      <Tabs.Screen name="index" options={{ title: '홈', headerShown: false }} />
      <Tabs.Screen name="community" options={{ title: '커뮤니티', headerShown: false }} />
      {/* 남기기는 화면이 아니다. 탭 버튼을 바꿔 선택 시트만 열고 이 경로로는 이동하지 않는다. */}
      <Tabs.Screen name="leave" options={{ title: '남기기', tabBarButton: () => <LeaveTabButton /> }} />
      <Tabs.Screen name="my" options={{ title: '마이', headerShown: false }} />
    </Tabs>
  );
}
