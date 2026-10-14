// SCR-14 내 체감 기록 (07 SCR-14, 04 FR-FEL-11): 최근 30일 기록(시각·동네·태그·출처)과 feeling이 낸 내 패턴을 보여주기만 한다.
import { router } from 'expo-router';
import { ActivityIndicator, StyleSheet, View } from 'react-native';

import { AppText, Button, Screen, SectionState } from '@/components/ui';
import { useViewingState } from '@/neighborhood/viewingStore';
import { routes } from '@/navigation/routes';
import { useSession } from '@/session/useSession';
import { colors, spacing } from '@/theme';

import { FeelingPatternView, FeelingRecordRow } from './components';
import { useMyFeelingRecords } from './MyPageScreen';
import { MembersOnly } from './MyPostsScreen';

export const MY_RECORDS_EMPTY = '최근 30일 동안 남긴 기록이 없어요';

export function MyFeelingRecordsScreen() {
  const session = useSession();
  const viewing = useViewingState();
  const isMember = session.status === 'member';
  const records = useMyFeelingRecords(isMember);

  if (!isMember) {
    return <MembersOnly />;
  }
  if (records.isPending) {
    return <ActivityIndicator style={styles.loading} color={colors.primary} />;
  }
  if (records.isError || !records.data) {
    return (
      <Screen>
        <SectionState status="failed" failedMessage="기록을 불러오지 못했어요" onRetry={() => records.refetch()} />
      </Screen>
    );
  }
  const { records: items, pattern } = records.data;
  return (
    <Screen>
      <FeelingPatternView pattern={pattern} />
      {items.length === 0 ? (
        <SectionState
          status="empty"
          emptyMessage={MY_RECORDS_EMPTY}
          emptyAction={
            // 현재 동네를 알 때만 바로 기록하러 가게 한다(판정 불가면 기록 화면이 막는다).
            viewing.current ? (
              <Button title="체감 기록 남기기" onPress={() => router.push(routes.feelingRecord())} testID="records-write" />
            ) : null
          }
        />
      ) : (
        <View>
          {items.map((record) => (
            <FeelingRecordRow key={record.id.value} record={record} />
          ))}
        </View>
      )}
      <AppText variant="caption" tone="secondary">
        기록은 고치거나 지울 수 없어요
      </AppText>
    </Screen>
  );
}

const styles = StyleSheet.create({
  loading: { marginTop: spacing.xl },
});
