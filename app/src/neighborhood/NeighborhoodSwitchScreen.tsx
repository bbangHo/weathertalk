// SCR-02 동네 전환 시트 / 관심 동네 관리 (07 SCR-02, §2 규칙 3, §4 규칙 2, §6.1)
// 회원은 서버 관심 동네, 비회원은 기기 저장. 저장 실패 시 목록을 되돌리고 알린다.
import { router } from 'expo-router';
import { useEffect, useState } from 'react';
import { Linking, Pressable, ScrollView, StyleSheet, View } from 'react-native';

import { api } from '@/api';
import { AppText, Button, SectionState } from '@/components/ui';
import { getLocationPermission, requestLocationPermission } from '@/location/deviceLocation';
import { judgeCurrentNeighborhood } from '@/location/judgeCurrentNeighborhood';
import {
  canOfferLocationPreprompt,
  clearLocationDeclined,
  recordLocationDeclined,
} from '@/location/prepromptHistory';
import { routes } from '@/navigation/routes';
import { Alert } from '@/platform/alert';
import { sessionStore } from '@/session/sessionStore';
import { colors, radii, spacing } from '@/theme';

import type { LocationPermission } from '@/entry/entryDecision';

import { setMemberCurrentNeighborhood } from './api';
import { useInterestNeighborhoods, useNeighborhoodDetail } from './hooks';
import type { NeighborhoodSummary } from './types';
import { useViewingState, viewingStore } from './viewingStore';

export type NeighborhoodSwitchMode = 'switch' | 'manage';

function close() {
  if (router.canGoBack()) {
    router.back();
  }
}

export function NeighborhoodSwitchScreen({ mode = 'switch' }: { mode?: NeighborhoodSwitchMode }) {
  const viewing = useViewingState();
  const interests = useInterestNeighborhoods();
  const [editing, setEditing] = useState(mode === 'manage');
  const [error, setError] = useState<string | null>(null);

  const list = interests.query.data ?? [];
  const full = list.length >= interests.max;
  const interestIds = list.map((item) => item.id);
  const viewingId = viewing.viewingNeighborhoodId;
  const viewingIsSearched =
    viewingId !== null && viewingId !== viewing.current?.neighborhoodId && !interestIds.includes(viewingId);

  const choose = async (neighborhoodId: string) => {
    await viewingStore.setViewing(neighborhoodId);
    if (mode === 'switch') {
      close();
    }
  };

  const run = async (action: () => Promise<{ ok: boolean; message?: string }>) => {
    setError(null);
    const result = await action();
    if (!result.ok) {
      setError(result.message ?? '저장하지 못했어요.');
    }
  };

  return (
    <ScrollView style={styles.root} contentContainerStyle={styles.content}>
      <CurrentNeighborhoodRow
        selectable={mode === 'switch'}
        isViewing={viewingId !== null && viewingId === viewing.current?.neighborhoodId}
        onChoose={choose}
      />

      {viewingIsSearched && mode === 'switch' ? <SearchedViewingRow neighborhoodId={viewingId} /> : null}

      <View style={styles.sectionHeader}>
        <AppText variant="heading">관심 동네</AppText>
        {list.length > 0 ? (
          <Pressable accessibilityRole="button" onPress={() => setEditing((v) => !v)} testID="interest-edit-toggle">
            <AppText tone="primary">{editing ? '완료' : '편집'}</AppText>
          </Pressable>
        ) : null}
      </View>

      {error ? (
        <AppText tone="danger" accessibilityRole="alert" testID="interest-error">
          {error}
        </AppText>
      ) : null}

      {interests.query.isError ? (
        <SectionState status="failed" failedMessage="관심 동네를 불러오지 못했어요" onRetry={() => interests.query.refetch()} />
      ) : interests.query.isPending ? null : list.length === 0 ? (
        <SectionState status="empty" emptyMessage="자주 보는 동네를 추가해 보세요" />
      ) : (
        list.map((item, index) => (
          <InterestRow
            key={item.id}
            neighborhood={item}
            isViewing={item.id === viewingId}
            editing={editing}
            selectable={mode === 'switch'}
            first={index === 0}
            last={index === list.length - 1}
            onChoose={() => choose(item.id)}
            onRemove={() => run(() => interests.actions.remove(item.id))}
            onMove={(delta) => run(() => interests.actions.move(item.id, delta))}
          />
        ))
      )}

      <Button
        title="동네 추가"
        variant="secondary"
        disabled={full || interests.query.isError}
        onPress={() => router.push(routes.neighborhoodSearch())}
        testID="interest-add"
      />
      {full ? (
        <AppText variant="caption" tone="secondary" testID="interest-full">
          {`관심 동네는 ${interests.max}개까지 추가할 수 있어요. 다른 동네를 지우면 추가할 수 있어요.`}
        </AppText>
      ) : null}
    </ScrollView>
  );
}

function CurrentNeighborhoodRow({
  selectable,
  isViewing,
  onChoose,
}: {
  selectable: boolean;
  isViewing: boolean;
  onChoose: (id: string) => void;
}) {
  const viewing = useViewingState();
  const [permission, setPermission] = useState<LocationPermission | null>(null);
  const [canAskAgain, setCanAskAgain] = useState(true);
  const [preprompt, setPreprompt] = useState(false);
  const [suggest, setSuggest] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const currentId = viewing.current?.neighborhoodId ?? null;
  const detail = useNeighborhoodDetail(permission === 'granted' ? currentId : null);

  useEffect(() => {
    let alive = true;
    Promise.all([getLocationPermission(), canOfferLocationPreprompt()])
      .then(([result, offer]) => {
        if (!alive) {
          return;
        }
        setPermission(result.permission);
        setCanAskAgain(result.canAskAgain);
        setSuggest(result.permission !== 'granted' && offer);
      })
      .catch(() => undefined);
    return () => {
      alive = false;
    };
  }, []);

  const judge = async () => {
    setMessage(null);
    const result = await judgeCurrentNeighborhood({ store: viewingStore });
    if (result.status !== 'resolved') {
      setMessage('현재 동네를 찾지 못했어요. 잠시 후 다시 시도해 주세요.');
      return;
    }
    if (sessionStore.getState().status !== 'member') {
      return;
    }
    // 판정은 기기 안에서 끝났다. 회원의 서버 현재 동네는 사용자가 확인한 동네 ID로만 바꾼다(ADR 0009).
    const { id, eupmyeondongName } = result.neighborhood;
    Alert.alert(`지금 위치는 ${eupmyeondongName}이에요`, '이 동네로 할까요?', [
      { text: '아니요', style: 'cancel' },
      {
        text: '이 동네로 할게요',
        onPress: () => {
          setMemberCurrentNeighborhood(api, id).catch(() =>
            setMessage('현재 동네를 저장하지 못했어요. 잠시 후 다시 시도해 주세요.'),
          );
        },
      },
    ]);
  };

  const enable = async () => {
    setPreprompt(false);
    const result = await requestLocationPermission();
    setPermission(result.permission);
    setCanAskAgain(result.canAskAgain);
    if (result.permission === 'granted') {
      await clearLocationDeclined().catch(() => undefined);
      await judge();
    } else {
      await recordLocationDeclined().catch(() => undefined);
      setMessage(result.canAskAgain ? '위치를 켜지 않았어요.' : '설정에서 위치 권한을 켜 주세요.');
    }
  };

  const decline = async () => {
    setPreprompt(false);
    setSuggest(false);
    await recordLocationDeclined().catch(() => undefined);
  };

  if (permission === null) {
    return null;
  }

  if (permission !== 'granted') {
    return (
      <View style={styles.row} testID="current-row-off">
        <View style={styles.rowText}>
          <AppText variant="caption" tone="secondary">
            현재 동네
          </AppText>
          <AppText>위치 사용 안 함</AppText>
          {suggest ? (
            <AppText variant="caption" tone="secondary">
              위치를 켜면 지금 있는 동네 날씨를 바로 볼 수 있어요.
            </AppText>
          ) : null}
        </View>
        {preprompt ? (
          <View style={styles.preprompt} testID="location-preprompt">
            <AppText>현재 동네를 찾으려면 위치가 필요해요. 위치는 동네 판정에만 쓰고 저장하지 않아요.</AppText>
            <Button title="위치 켜기" onPress={enable} testID="location-preprompt-accept" />
            <Button title="나중에" variant="secondary" onPress={decline} testID="location-preprompt-decline" />
          </View>
        ) : canAskAgain ? (
          <Button title="켜기" variant="secondary" onPress={() => setPreprompt(true)} testID="location-enable" />
        ) : (
          <Button title="설정 열기" variant="secondary" onPress={() => Linking.openSettings()} />
        )}
        {message ? <AppText variant="caption" tone="danger">{message}</AppText> : null}
      </View>
    );
  }

  const name = detail.data?.eupmyeondongName;
  return (
    <Pressable
      accessibilityRole="button"
      disabled={!selectable || currentId === null}
      onPress={() => currentId && onChoose(currentId)}
      style={[styles.row, isViewing && styles.rowViewing]}
      testID="current-row"
    >
      <View style={styles.rowText}>
        <AppText variant="caption" tone="secondary">
          📍 현재 동네
        </AppText>
        <AppText variant="button">{currentId === null ? '아직 찾지 못했어요' : (name ?? '불러오는 중')}</AppText>
        {isViewing ? (
          <AppText variant="caption" tone="primary">
            보는 중
          </AppText>
        ) : null}
        {message ? <AppText variant="caption" tone="danger">{message}</AppText> : null}
      </View>
      {currentId === null ? <Button title="다시 찾기" variant="secondary" onPress={judge} /> : null}
    </Pressable>
  );
}

function SearchedViewingRow({ neighborhoodId }: { neighborhoodId: string }) {
  const detail = useNeighborhoodDetail(neighborhoodId);
  return (
    <View style={[styles.row, styles.rowViewing]} testID="searched-row">
      <View style={styles.rowText}>
        <AppText variant="caption" tone="secondary">
          둘러보는 동네 (보기 전용)
        </AppText>
        <AppText variant="button">{detail.data?.eupmyeondongName ?? '불러오는 중'}</AppText>
        <AppText variant="caption" tone="primary">
          보는 중
        </AppText>
      </View>
    </View>
  );
}

function InterestRow(props: {
  neighborhood: NeighborhoodSummary;
  isViewing: boolean;
  editing: boolean;
  selectable: boolean;
  first: boolean;
  last: boolean;
  onChoose: () => void;
  onRemove: () => Promise<unknown>;
  onMove: (delta: -1 | 1) => Promise<unknown>;
}) {
  const { neighborhood, isViewing, editing, selectable, first, last } = props;
  return (
    <Pressable
      accessibilityRole="button"
      disabled={editing || !selectable}
      onPress={props.onChoose}
      style={[styles.row, isViewing && styles.rowViewing]}
      testID={`interest-row-${neighborhood.id}`}
    >
      <View style={styles.rowText}>
        <AppText variant="button">{neighborhood.eupmyeondongName}</AppText>
        <AppText variant="caption" tone="secondary">
          {`${neighborhood.sidoName} ${neighborhood.sigunguName}`}
        </AppText>
        {isViewing ? (
          <AppText variant="caption" tone="primary">
            보는 중
          </AppText>
        ) : null}
      </View>
      {editing ? (
        <View style={styles.editButtons}>
          <Button title="↑" variant="secondary" disabled={first} onPress={() => props.onMove(-1)} testID={`interest-up-${neighborhood.id}`} />
          <Button title="↓" variant="secondary" disabled={last} onPress={() => props.onMove(1)} testID={`interest-down-${neighborhood.id}`} />
          <Button title="삭제" variant="secondary" onPress={props.onRemove} testID={`interest-remove-${neighborhood.id}`} />
        </View>
      ) : null}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.background },
  content: { padding: spacing.lg, gap: spacing.md },
  sectionHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginTop: spacing.sm },
  row: {
    backgroundColor: colors.surface,
    borderRadius: radii.md,
    padding: spacing.md,
    gap: spacing.sm,
  },
  rowViewing: { borderColor: colors.primary },
  rowText: { gap: 2 },
  preprompt: { gap: spacing.sm },
  editButtons: { flexDirection: 'row', gap: spacing.sm },
});
