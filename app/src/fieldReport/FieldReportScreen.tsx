// SCR-05 현장 제보 (07 SCR-05, §3.1, 03 FR-WEA-04·05·14, BR-WEA-15·16·16a)
// - 보는 동네와 무관하게 현재 동네에 제보한다(SCR-04와 같은 규칙: 판정이 오래됐으면 재판정, 판정 불가면 막음).
// - 축마다 따로 제보한다. 본인 유효 제보는 선택 상태로 보이고, 서버가 준 취소 기한 안에서만 "제보 취소"를 보여준다.
// - 비회원은 제보 때 가입 유도(선택 보존, 자동 제출 없음). 실패는 선택을 유지하고 같은 요청 식별자로 다시 시도한다.
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, View } from 'react-native';

import { api } from '@/api';
import { isApiError } from '@/api/errors';
import { createIdempotencyKeyHolder } from '@/api/idempotency';
import { AppText, Button, Screen, SectionState } from '@/components/ui';
import { kstTimeText } from '@/home/format';
import { prepareWriteLocation } from '@/location/writeLocation';
import { useNeighborhoodDetail } from '@/neighborhood/hooks';
import { useViewingState, viewingStore } from '@/neighborhood/viewingStore';
import { type PendingAction, useRestoredAction } from '@/pending/pendingAction';
import { writeGate } from '@/pending/writeGate';
import { queryKeys } from '@/query/queryKeys';
import { useSession } from '@/session/useSession';
import { colors, radii, spacing } from '@/theme';

import { cancelFieldReport, getFieldReportStatus, submitFieldReport } from './api';
import {
  AXES,
  AXIS_KINDS,
  AXIS_LABEL,
  CANCEL_WINDOW_PASSED_MESSAGE,
  isCancelable,
  KIND_LABEL,
  nextCancelDeadline,
  REPORT_LOCATION_UNAVAILABLE,
} from './labels';
import type { AxisStatus, FieldReport, FieldReportAxis, FieldReportKind, FieldReportResult, FieldReportStatus } from './types';

export const FIELD_REPORT_ROUTE = '/field-report';
const CANCEL_WINDOW_PASSED = 'FIELD_REPORT_CANCEL_WINDOW_PASSED';

type Picked = Partial<Record<FieldReportAxis, FieldReportKind>>;
type PerAxis = Partial<Record<FieldReportAxis, string>>;

/** 회원/비회원 응답(내 제보 포함 여부)이 섞이지 않게 범위를 키에 넣는다. 무효화는 동네 키로 함께 된다. */
export function fieldReportStatusKey(neighborhoodId: string, isMember: boolean) {
  return [...queryKeys.weather.fieldReports(neighborhoodId), isMember ? 'member' : 'guest'] as const;
}

/** 보류 행동에서 꺼낸 값은 기기 저장분이라 아는 축·종류만 받는다. */
function toPicked(selections: Record<string, string>): Picked {
  const picked: Picked = {};
  for (const axis of AXES) {
    const kind = selections[axis] as FieldReportKind | undefined;
    if (kind && AXIS_KINDS[axis].includes(kind)) {
      picked[axis] = kind;
    }
  }
  return picked;
}

export function FieldReportScreen() {
  const queryClient = useQueryClient();
  const viewing = useViewingState();
  const session = useSession();
  const isMember = session.status === 'member';
  const currentId = viewing.current?.neighborhoodId ?? null;
  const currentName = useNeighborhoodDetail(currentId);

  const status = useQuery({
    queryKey: fieldReportStatusKey(currentId ?? '', isMember),
    queryFn: () => getFieldReportStatus(api, currentId!),
    enabled: currentId !== null && session.status !== 'restoring',
  });

  const [picked, setPicked] = useState<Picked>({});
  const [errors, setErrors] = useState<PerAxis>({});
  const [done, setDone] = useState<PerAxis>({});
  const [notice, setNotice] = useState<string | null>(null);
  // 취소 기한 지남으로 거절된 제보는 서버 기한과 무관하게 버튼을 숨긴다.
  const [expired, setExpired] = useState<ReadonlySet<string>>(new Set());
  const [now, setNow] = useState(() => Date.now());
  // 축마다 한 행동에 요청 식별자 하나: 같은 종류의 다시 시도는 같은 식별자, 종류가 바뀌거나 성공하면 새 식별자.
  const keys = useRef<Partial<Record<FieldReportAxis, { kind: FieldReportKind; holder: ReturnType<typeof createIdempotencyKeyHolder> }>>>(
    {},
  );

  const myReports = status.data?.myReports ?? [];

  // 가장 이른 취소 기한에 화면을 다시 그려 버튼을 숨긴다.
  const deadline = nextCancelDeadline(myReports, now);
  useEffect(() => {
    if (deadline === null) {
      return;
    }
    const timer = setTimeout(() => setNow(Date.now()), Math.max(0, deadline - Date.now()) + 1);
    return () => clearTimeout(timer);
  }, [deadline]);

  useRestoredAction('fieldReport', ({ action, notice: restoredNotice }) => {
    setPicked(toPicked(action.payload.selections));
    setErrors({});
    setDone({});
    setNotice(restoredNotice ?? '고른 내용을 확인하고 제보해 주세요.');
  });

  const setAxis = (setter: typeof setErrors, axis: FieldReportAxis, value: string | null) =>
    setter((prev) => ({ ...prev, [axis]: value ?? undefined }));

  const pick = (axis: FieldReportAxis, kind: FieldReportKind) => {
    setPicked((prev) => ({ ...prev, [axis]: kind }));
    setAxis(setErrors, axis, null);
    setAxis(setDone, axis, null);
  };

  async function applyResult(result: FieldReportResult): Promise<void> {
    queryClient.setQueryData<FieldReportStatus>(
      fieldReportStatusKey(result.status.neighborhoodId.value, true),
      result.status,
    );
    await queryClient.invalidateQueries({ queryKey: queryKeys.home.all });
  }

  async function submit(axis: FieldReportAxis): Promise<void> {
    const kind = picked[axis];
    if (!kind) {
      return;
    }
    // 체감 기록처럼 보는 동네와 무관하게 현재 동네에 남기므로 동네 ID는 비워 둔다(복원 때 판정만 되면 통과).
    const action: PendingAction = {
      kind: 'fieldReport',
      route: FIELD_REPORT_ROUTE,
      neighborhoodId: null,
      payload: { selections: { ...picked } },
    };
    if (!(await writeGate.requireMember(action))) {
      return;
    }
    if (keys.current[axis]?.kind !== kind) {
      keys.current[axis] = { kind, holder: createIdempotencyKeyHolder() };
    }
    const key = keys.current[axis]!.holder.current();
    setAxis(setErrors, axis, null);
    setAxis(setDone, axis, null);
    setNotice(null);
    const where = await prepareWriteLocation({ api, store: viewingStore, isMember: true });
    if (where.status !== 'ok') {
      setAxis(setErrors, axis, REPORT_LOCATION_UNAVAILABLE);
      return;
    }
    try {
      const result = await submitFieldReport(api, kind, where.location, key);
      delete keys.current[axis];
      setPicked((prev) => ({ ...prev, [axis]: undefined }));
      setAxis(setDone, axis, '제보했어요');
      await applyResult(result);
    } catch (cause) {
      if (await writeGate.handleWriteError(cause, action)) {
        return;
      }
      // 하루 한도·현재 동네 불일치 등 weather·neighborhood가 준 사유를 그대로 보여준다.
      setAxis(setErrors, axis, isApiError(cause) && cause.detail ? cause.detail : '제보하지 못했어요. 다시 시도해 주세요.');
    }
  }

  async function cancel(report: FieldReport): Promise<void> {
    setAxis(setErrors, report.axis, null);
    setAxis(setDone, report.axis, null);
    try {
      const result = await cancelFieldReport(api, report.reportId);
      setAxis(setDone, report.axis, '제보를 취소했어요');
      await applyResult(result);
    } catch (cause) {
      if (isApiError(cause) && cause.code === CANCEL_WINDOW_PASSED) {
        setExpired((prev) => new Set(prev).add(report.reportId));
        setAxis(setErrors, report.axis, CANCEL_WINDOW_PASSED_MESSAGE);
        return;
      }
      setAxis(setErrors, report.axis, isApiError(cause) && cause.detail ? cause.detail : '취소하지 못했어요. 다시 시도해 주세요.');
    }
  }

  return (
    <Screen>
      <View style={styles.block}>
        <AppText variant="caption" tone="secondary">
          제보는 지금 있는 동네(현재 동네)에 남아요
        </AppText>
        <AppText variant="heading" testID="report-neighborhood">
          {currentId ? (currentName.data?.fullName ?? '현재 동네') : '제보할 때 현재 동네를 확인해요'}
        </AppText>
        {notice ? (
          <AppText tone="primary" testID="report-notice">
            {notice}
          </AppText>
        ) : null}
      </View>

      {status.isPending && currentId ? <ActivityIndicator color={colors.primary} /> : null}
      {status.isError ? (
        <SectionState status="failed" failedMessage="제보 현황을 불러오지 못했어요" onRetry={() => status.refetch()} />
      ) : null}

      {AXES.map((axis) => {
        const mine = myReports.find((report) => report.axis === axis && !report.canceledAt) ?? null;
        return (
          <AxisBlock
            key={axis}
            axis={axis}
            axisStatus={status.data?.axes.find((item) => item.axis === axis) ?? null}
            mine={mine}
            selected={picked[axis] ?? mine?.kind ?? null}
            canSubmit={picked[axis] !== undefined}
            cancelable={mine !== null && isCancelable(mine, now) && !expired.has(mine.reportId)}
            error={errors[axis] ?? null}
            done={done[axis] ?? null}
            onPick={(kind) => pick(axis, kind)}
            onSubmit={() => submit(axis)}
            onCancel={() => (mine ? cancel(mine) : undefined)}
          />
        );
      })}
    </Screen>
  );
}

function AxisBlock({
  axis,
  axisStatus,
  mine,
  selected,
  canSubmit,
  cancelable,
  error,
  done,
  onPick,
  onSubmit,
  onCancel,
}: {
  axis: FieldReportAxis;
  axisStatus: AxisStatus | null;
  mine: FieldReport | null;
  selected: FieldReportKind | null;
  canSubmit: boolean;
  cancelable: boolean;
  error: string | null;
  done: string | null;
  onPick: (kind: FieldReportKind) => void;
  onSubmit: () => Promise<void>;
  onCancel: () => Promise<void> | undefined;
}) {
  return (
    <View style={styles.card} testID={`axis-${axis}`}>
      <AppText variant="heading">{AXIS_LABEL[axis]}</AppText>
      {axisStatus ? (
        <AppText variant="caption" tone="secondary" testID={`axis-status-${axis}`}>
          {axisStatus.reporterCount === 0
            ? '아직 제보가 없어요'
            : `지금 ${axisStatus.reporterCount}명 제보 · ${axisStatus.counts
                .map((count) => `${KIND_LABEL[count.kind]} ${count.reporterCount}명`)
                .join(', ')}`}
        </AppText>
      ) : null}
      {mine ? (
        <AppText variant="caption" testID={`my-report-${axis}`}>
          {`내 제보: ${KIND_LABEL[mine.kind]}${kstTimeText(mine.reportedAt) ? ` (${kstTimeText(mine.reportedAt)})` : ''}`}
        </AppText>
      ) : null}
      <View style={styles.kinds}>
        {AXIS_KINDS[axis].map((kind) => {
          const active = kind === selected;
          return (
            <Pressable
              key={kind}
              accessibilityRole="radio"
              accessibilityState={{ selected: active }}
              onPress={() => onPick(kind)}
              style={[styles.kind, active && styles.kindActive]}
              testID={`kind-${kind}`}
            >
              <AppText tone={active ? 'onPrimary' : 'primary'}>{KIND_LABEL[kind]}</AppText>
            </Pressable>
          );
        })}
      </View>
      {error ? (
        <AppText tone="danger" accessibilityRole="alert" testID={`report-error-${axis}`}>
          {error}
        </AppText>
      ) : null}
      {done ? (
        <AppText tone="primary" testID={`report-done-${axis}`}>
          {done}
        </AppText>
      ) : null}
      <Button title={error && canSubmit ? '다시 시도' : '제보하기'} disabled={!canSubmit} onPress={onSubmit} testID={`submit-${axis}`} />
      {cancelable ? (
        <Button title="제보 취소" variant="secondary" onPress={onCancel} testID={`cancel-${axis}`} />
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  block: { gap: spacing.sm },
  card: {
    backgroundColor: colors.surface,
    borderRadius: radii.lg,
    padding: spacing.lg,
    gap: spacing.sm,
  },
  kinds: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm },
  kind: {
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    borderRadius: radii.pill,
    borderWidth: 1,
    borderColor: colors.border,
    backgroundColor: colors.background,
  },
  kindActive: { backgroundColor: colors.primary, borderColor: colors.primary },
});
