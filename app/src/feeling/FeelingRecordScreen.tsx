// SCR-04 체감 기록 작성 (07 SCR-04, §3.1, 04 FR-FEL-01·BR-FEL-03·04)
// - 보는 동네와 무관하게 현재 동네에 기록한다. 판정이 app.write-location-max-age보다 오래됐으면 저장 전에 재판정한다.
// - 아무 태그도 미리 선택하지 않는다(BR-FEL-03). 선택지·문구는 서버 태그 정의를 쓴다.
// - 비회원은 저장 때 가입 유도(선택 보존). 실패하면 선택 유지 + 같은 요청 식별자로 다시 시도.
import { useQueryClient } from '@tanstack/react-query';
import { router } from 'expo-router';
import { useRef, useState } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, View } from 'react-native';

import { api } from '@/api';
import { isApiError } from '@/api/errors';
import { useIdempotencyKey } from '@/api/idempotency';
import { AppText, Button, Screen, SectionState } from '@/components/ui';
import type { HomeView } from '@/home/types';
import { prepareWriteLocation } from '@/location/writeLocation';
import { useNeighborhoodDetail } from '@/neighborhood/hooks';
import { useViewingState, viewingStore } from '@/neighborhood/viewingStore';
import { routes } from '@/navigation/routes';
import { type PendingPayloads, useRestoredAction } from '@/pending/pendingAction';
import { writeGate } from '@/pending/writeGate';
import { queryKeys } from '@/query/queryKeys';
import { colors, radii, spacing } from '@/theme';

import { postFeelingRecord } from './api';
import { useFeelingTags } from './labels';
import type { FeelingRecordResult, HumidityTagName, TagDefinition, TemperatureTagName, WindTagName } from './types';

type Selection = PendingPayloads['feelingRecord'];

const EMPTY: Selection = { temperature: null, humidity: null, wind: null };
export const RECORD_LOCATION_UNAVAILABLE = '현재 동네를 확인할 수 없어 기록할 수 없어요';
const RECORD_ROUTE = '/feeling-record';

/** 기록 간격 안의 내 기록이 있는가: 홈 응답의 피드백 상태(현재 동네일 때만 실림)를 쓴다. */
function useHasRecentRecord(currentId: string | null): boolean {
  const queryClient = useQueryClient();
  const [openedAt] = useState(() => Date.now());
  if (!currentId) {
    return false;
  }
  const home = queryClient.getQueryData<HomeView>(queryKeys.home.byNeighborhood(currentId));
  const until = home?.feedbackStatus?.data?.recentRecord?.overwritableUntil;
  return until ? Date.parse(until) > openedAt : false;
}

export function FeelingRecordScreen() {
  const queryClient = useQueryClient();
  const viewing = useViewingState();
  const tags = useFeelingTags();
  const key = useIdempotencyKey();
  const attempted = useRef<string | null>(null);
  const [selection, setSelection] = useState<Selection>(EMPTY);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState<FeelingRecordResult | null>(null);

  const currentId = viewing.current?.neighborhoodId ?? null;
  const currentName = useNeighborhoodDetail(currentId);
  const hasRecent = useHasRecentRecord(currentId);

  useRestoredAction('feelingRecord', ({ action, notice: restoredNotice }) => {
    setSelection(action.payload);
    setSaved(null);
    setNotice(restoredNotice ?? '고른 내용을 확인하고 저장해 주세요.');
  });

  const select = (patch: Partial<Selection>) => {
    setSelection((prev) => ({ ...prev, ...patch }));
    setError(null);
  };

  async function save(): Promise<void> {
    if (!selection.temperature) {
      return;
    }
    const action = { kind: 'feelingRecord' as const, route: RECORD_ROUTE, neighborhoodId: null, payload: selection };
    if (!(await writeGate.requireMember(action))) {
      return;
    }
    // 같은 선택의 다시 시도는 같은 요청 식별자, 선택이 바뀌면 새 식별자(서버가 중복을 한 번으로 처리).
    const signature = JSON.stringify(selection);
    if (attempted.current !== signature) {
      key.renew();
      attempted.current = signature;
    }
    setError(null);
    setNotice(null);
    const where = await prepareWriteLocation({ store: viewingStore });
    if (where.status === 'cancelled') {
      return;
    }
    if (where.status !== 'ok') {
      setError(RECORD_LOCATION_UNAVAILABLE);
      return;
    }
    try {
      const result = await postFeelingRecord(
        api,
        { temperature: selection.temperature, humidity: selection.humidity, wind: selection.wind },
        where.neighborhoodId,
        key.current(),
      );
      attempted.current = null;
      key.renew();
      setSaved(result);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.home.all }),
        queryClient.invalidateQueries({ queryKey: queryKeys.me.feelingRecords() }),
      ]);
    } catch (cause) {
      if (await writeGate.handleWriteError(cause, action)) {
        return;
      }
      // 현재 동네 불일치 등 feeling이 준 사유는 그대로 보여준다.
      setError(isApiError(cause) && cause.detail ? cause.detail : '저장하지 못했어요. 다시 시도해 주세요.');
    }
  }

  if (saved) {
    return (
      <Screen>
        <View style={styles.block} testID="record-saved">
          <AppText variant="title">기록했어요</AppText>
          {saved.overwritten ? <AppText tone="secondary">이전 기록을 바꿨어요</AppText> : null}
          <Button
            title="글로도 남기기"
            onPress={() => router.replace(routes.postEditor({ feelingRecordId: saved.recordId }))}
            testID="record-write-post"
          />
          <Button title="닫기" variant="secondary" onPress={() => router.back()} testID="record-close" />
        </View>
      </Screen>
    );
  }

  return (
    <Screen>
      <View style={styles.block}>
        <AppText variant="caption" tone="secondary">
          기록은 지금 있는 동네(현재 동네)에 남아요
        </AppText>
        <AppText variant="heading" testID="record-neighborhood">
          {currentId ? (currentName.data?.fullName ?? '현재 동네') : '저장할 때 현재 동네를 확인해요'}
        </AppText>
        {hasRecent ? <AppText tone="secondary">이전 기록을 바꿔요</AppText> : null}
        {notice ? (
          <AppText tone="primary" testID="record-notice">
            {notice}
          </AppText>
        ) : null}
      </View>

      {tags.isPending ? <ActivityIndicator color={colors.primary} /> : null}
      {tags.isError ? (
        <SectionState status="failed" failedMessage="선택지를 불러오지 못했어요" onRetry={() => tags.refetch()} />
      ) : null}
      {tags.data ? (
        <>
          <TagGroup
            title="온도 (필수)"
            tags={tags.data.temperature}
            selected={selection.temperature}
            onSelect={(name) => select({ temperature: name as TemperatureTagName })}
            testPrefix="temperature"
          />
          <TagGroup
            title="습도 (선택)"
            tags={tags.data.humidity}
            selected={selection.humidity}
            onSelect={(name) => select({ humidity: name as HumidityTagName | null })}
            optional
            testPrefix="humidity"
          />
          <TagGroup
            title="바람 (선택)"
            tags={tags.data.wind}
            selected={selection.wind}
            onSelect={(name) => select({ wind: name as WindTagName | null })}
            optional
            testPrefix="wind"
          />
        </>
      ) : null}

      {error ? (
        <AppText tone="danger" accessibilityRole="alert" testID="record-error">
          {error}
        </AppText>
      ) : null}
      <Button
        title={error ? '다시 시도' : '저장'}
        disabled={!selection.temperature}
        onPress={save}
        testID="record-save"
      />
    </Screen>
  );
}

function TagGroup({
  title,
  tags,
  selected,
  onSelect,
  optional = false,
  testPrefix,
}: {
  title: string;
  tags: TagDefinition[];
  selected: string | null;
  onSelect: (name: string | null) => void;
  optional?: boolean;
  testPrefix: string;
}) {
  const ordered = [...tags].sort((a, b) => a.level - b.level);
  return (
    <View style={styles.block}>
      <AppText variant="heading">{title}</AppText>
      <View style={styles.tags}>
        {ordered.map((tag) => {
          const active = tag.name === selected;
          return (
            <Pressable
              key={tag.name}
              accessibilityRole="radio"
              accessibilityState={{ selected: active }}
              // 선택 항목은 다시 누르면 고르지 않은 상태로 돌린다.
              onPress={() => onSelect(active && optional ? null : tag.name)}
              style={[styles.tag, active && styles.tagActive]}
              testID={`${testPrefix}-${tag.name}`}
            >
              <AppText tone={active ? 'onPrimary' : 'primary'}>{tag.label}</AppText>
            </Pressable>
          );
        })}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  block: { gap: spacing.sm },
  tags: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm },
  tag: {
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    borderRadius: radii.pill,
    borderWidth: 1,
    borderColor: colors.border,
    backgroundColor: colors.surface,
  },
  tagActive: { backgroundColor: colors.primary, borderColor: colors.primary },
});
