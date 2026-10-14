// 마이페이지·SCR-13·SCR-14 공용 표시 조각. 패턴 산출은 feeling이 하고 앱은 받은 값을 보여주기만 한다(07 SCR-14).
import { Pressable, StyleSheet, View } from 'react-native';

import { AppText } from '@/components/ui';
import { kstDateTimeText, relativeTimeText } from '@/community/format';
import type { PostSummary } from '@/community/types';
import { createTagLabeler, useFeelingTags } from '@/feeling/labels';
import type { FeelingRecordSource, TemperatureTagName } from '@/feeling/types';
import { colors, radii, spacing } from '@/theme';

import type { MyFeelingPattern, MyFeelingRecordItem } from './api';

const SOURCE_LABEL: Record<FeelingRecordSource, string> = {
  DIRECT: '직접 기록',
  FEEDBACK: '피드백',
  DIRECT_WITH_FEEDBACK: '직접 기록(피드백 반영)',
};

const TEMPERATURE_ORDER: TemperatureTagName[] = [
  'VERY_COLD',
  'COLD',
  'SLIGHTLY_COLD',
  'JUST_RIGHT',
  'SLIGHTLY_HOT',
  'HOT',
  'VERY_HOT',
];

export function FeelingRecordRow({ record }: { record: MyFeelingRecordItem }) {
  const tags = useFeelingTags();
  const label = createTagLabeler(tags.data);
  const parts = [label(record.temperatureTag), label(record.humidityTag), label(record.windTag)].filter(Boolean);
  return (
    <View style={styles.row} testID={`feeling-record-${record.id.value}`}>
      <AppText variant="caption" tone="secondary">
        {`${kstDateTimeText(record.recordedAt)} · ${record.neighborhoodName ?? '알 수 없는 동네'} · ${SOURCE_LABEL[record.source] ?? record.source}`}
      </AppText>
      <AppText>{parts.join(' · ')}</AppText>
    </View>
  );
}

export function FeelingPatternView({ pattern }: { pattern: MyFeelingPattern }) {
  const tags = useFeelingTags();
  const label = createTagLabeler(tags.data);
  const distribution = TEMPERATURE_ORDER.map((tag) => ({ tag, count: pattern.temperatureDistribution?.[tag] ?? 0 })).filter(
    (item) => item.count > 0,
  );
  return (
    <View style={styles.pattern} testID="feeling-pattern">
      <AppText variant="caption" tone="secondary">{`최근 30일 기록 ${pattern.recordCount}건`}</AppText>
      {pattern.tendencyPhrase ? <AppText testID="feeling-tendency">{pattern.tendencyPhrase}</AppText> : null}
      {distribution.length > 0 ? (
        <AppText variant="caption" tone="secondary">
          {distribution.map((item) => `${label(item.tag)} ${item.count}`).join(' · ')}
        </AppText>
      ) : null}
    </View>
  );
}

/** 내가 쓴 글 카드(SCR-13): 동네 이름, 작성 시각, 본문 미리보기, 좋아요·댓글 수 */
export function MyPostRow({ post, onOpen }: { post: PostSummary; onOpen: () => void }) {
  return (
    <Pressable accessibilityRole="button" onPress={onOpen} style={styles.row} testID={`my-post-${post.postId}`}>
      <AppText variant="caption" tone="secondary">
        {`${post.neighborhood.shortName} · ${relativeTimeText(post.createdAt)}`}
      </AppText>
      <AppText numberOfLines={2}>{post.body}</AppText>
      <AppText variant="caption" tone="secondary">{`♡ ${post.likeCount} · 댓글 ${post.commentCount}`}</AppText>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  row: {
    gap: 2,
    paddingVertical: spacing.sm,
    borderBottomWidth: 1,
    borderBottomColor: colors.border,
  },
  pattern: {
    gap: spacing.xs,
    padding: spacing.md,
    borderRadius: radii.md,
    backgroundColor: colors.background,
  },
});
