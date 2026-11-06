// 넓은 화면(PC 웹)에서 하단 탭 대신 쓰는 왼쪽 사이드바: 탭 · 남기기 · 내 동네 목록 (PC-3 시안)
import Ionicons from '@expo/vector-icons/Ionicons';
import type { Tabs } from 'expo-router';
import type { ComponentProps } from 'react';
import { Image, Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { AppText } from '@/components/ui';
import { useInterestNeighborhoods, useNeighborhoodDetail } from '@/neighborhood/hooks';
import { useViewingState, viewingStore } from '@/neighborhood/viewingStore';
import { colors, radii, spacing } from '@/theme';

import { LeaveTabButton } from './LeaveTabButton';

type TabBarProps = Parameters<NonNullable<ComponentProps<typeof Tabs>['tabBar']>>[0];

export const SIDE_BAR_WIDTH = 248;

export function SideBar({ state, descriptors, navigation }: TabBarProps) {
  return (
    <SafeAreaView style={styles.root} edges={['top', 'bottom', 'left']} testID="side-bar">
      <ScrollView contentContainerStyle={styles.content}>
        <View style={styles.logoRow}>
          <Image source={require('../../../assets/icon.png')} style={styles.logo} accessibilityIgnoresInvertColors />
          <AppText variant="heading">날씨톡톡</AppText>
        </View>

        {state.routes.map((route, index) => {
          if (route.name === 'leave') {
            return null;
          }
          const { options } = descriptors[route.key];
          const focused = state.index === index;
          const color = focused ? colors.primary : colors.textSecondary;
          // 하단 탭과 같은 규칙: 이미 고른 탭을 다시 누르면 tabPress만 알리고(스택 맨 위로 등) 이동하지 않는다.
          const onPress = () => {
            const event = navigation.emit({ type: 'tabPress', target: route.key, canPreventDefault: true });
            if (!focused && !event.defaultPrevented) {
              navigation.navigate(route.name, route.params);
            }
          };
          return (
            <Pressable
              key={route.key}
              accessibilityRole="tab"
              accessibilityState={{ selected: focused }}
              onPress={onPress}
              style={[styles.item, focused && styles.itemOn]}
              testID={`side-bar-${route.name}`}
            >
              {options.tabBarIcon?.({ focused, color, size: 20 })}
              <AppText style={[styles.itemLabel, { color }, focused && styles.itemLabelOn]}>
                {options.title ?? route.name}
              </AppText>
            </Pressable>
          );
        })}

        <View style={styles.leave}>
          <LeaveTabButton wide />
        </View>

        <NeighborhoodList />
      </ScrollView>
    </SafeAreaView>
  );
}

/** 현재 동네 + 관심 동네. 누르면 보는 동네가 바뀌고, 홈·커뮤니티가 그 동네로 바뀐다. */
function NeighborhoodList() {
  const viewing = useViewingState();
  const interests = useInterestNeighborhoods();
  const currentId = viewing.current?.neighborhoodId ?? null;
  const current = useNeighborhoodDetail(currentId);
  const rows = [
    ...(current.data ? [{ id: current.data.id, name: current.data.eupmyeondongName, sub: '현재 동네' }] : []),
    ...(interests.query.data ?? [])
      .filter((item) => item.id !== currentId)
      .map((item) => ({ id: item.id, name: item.eupmyeondongName, sub: item.sigunguName })),
  ];
  if (rows.length === 0) {
    return null;
  }
  return (
    <View style={styles.places}>
      <AppText variant="caption" tone="secondary" style={styles.placesTitle}>
        동네
      </AppText>
      {rows.map((row) => {
        const on = row.id === viewing.viewingNeighborhoodId;
        return (
          <Pressable
            key={row.id}
            accessibilityRole="button"
            accessibilityState={{ selected: on }}
            accessibilityLabel={`${row.name} 보기`}
            onPress={() => viewingStore.setViewing(row.id).catch(() => undefined)}
            style={[styles.place, on && styles.placeOn]}
            testID={`side-bar-place-${row.id}`}
          >
            <Ionicons name="location" size={16} color={on ? colors.primary : colors.textDisabled} />
            <View>
              <AppText style={on ? styles.itemLabelOn : undefined}>{row.name}</AppText>
              <AppText variant="caption" style={{ color: on ? colors.primary : colors.textSecondary }}>
                {row.sub}
              </AppText>
            </View>
          </Pressable>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  root: {
    width: SIDE_BAR_WIDTH,
    backgroundColor: 'rgba(255, 255, 255, 0.7)',
    borderRightWidth: 1,
    borderRightColor: colors.border,
  },
  content: { paddingTop: spacing.xl, paddingHorizontal: spacing.lg, paddingBottom: spacing.xl, gap: spacing.xs },
  logoRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    paddingHorizontal: spacing.sm,
    paddingBottom: spacing.lg,
  },
  logo: { width: 32, height: 32, borderRadius: 8 },
  item: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.md,
    paddingVertical: 10,
    paddingHorizontal: spacing.md,
    borderRadius: radii.md,
  },
  itemOn: { backgroundColor: colors.primarySoft },
  itemLabel: { fontWeight: '600' },
  itemLabelOn: { fontWeight: '700', color: colors.primary },
  leave: { paddingTop: spacing.md },
  places: { paddingTop: spacing.xl, gap: spacing.xs },
  placesTitle: { paddingHorizontal: spacing.md, fontWeight: '700' },
  place: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    paddingVertical: spacing.sm,
    paddingHorizontal: spacing.md,
    borderRadius: radii.md,
  },
  placeOn: { backgroundColor: colors.surface },
});
