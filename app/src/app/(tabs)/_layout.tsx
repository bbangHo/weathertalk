// 하단 탭: 홈 / 커뮤니티 / [남기기] / 마이 (07 §4)
import Ionicons from '@expo/vector-icons/Ionicons';
import { Tabs } from 'expo-router';
import type { ComponentProps } from 'react';

import { LeaveTabButton } from '@/components/navigation/LeaveTabButton';
import { colors } from '@/theme';

type IconProps = ComponentProps<typeof Ionicons>;

// 선택된 탭은 채운 아이콘, 나머지는 선 아이콘
function tabIcon(name: IconProps['name'], outline: IconProps['name']) {
  return function TabIcon({ focused, color, size }: { focused: boolean; color: IconProps['color']; size: number }) {
    return <Ionicons name={focused ? name : outline} color={color} size={size} />;
  };
}

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
      <Tabs.Screen
        name="index"
        options={{ title: '홈', headerShown: false, tabBarIcon: tabIcon('home', 'home-outline') }}
      />
      <Tabs.Screen
        name="community"
        options={{ title: '커뮤니티', headerShown: false, tabBarIcon: tabIcon('chatbubbles', 'chatbubbles-outline') }}
      />
      {/* 남기기는 화면이 아니다. 탭 버튼을 바꿔 선택 시트만 열고 이 경로로는 이동하지 않는다. */}
      <Tabs.Screen name="leave" options={{ title: '남기기', tabBarButton: () => <LeaveTabButton /> }} />
      <Tabs.Screen
        name="my"
        options={{ title: '마이', headerShown: false, tabBarIcon: tabIcon('person', 'person-outline') }}
      />
    </Tabs>
  );
}
