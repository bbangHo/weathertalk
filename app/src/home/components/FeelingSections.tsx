// SCR-01 4·5·8·9번 + 민감도 보정 제안 카드 (07 SCR-01, §1.2·1.3, 04 FR-FEL-04·05·08)
// 내 그룹 강조·옷 추천 기준 그룹은 home 응답(personal)을 그대로 쓴다. 판단은 하지 않는다(§1.5).
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';

import { api } from '@/api';
import { isApiError } from '@/api/errors';
import { changeTemperatureSensitivity, SENSITIVITY_LABEL, SENSITIVITY_ORDER } from '@/auth/api';
import { AppText, Button, SectionState } from '@/components/ui';
import { acceptCalibrationOffer, declineCalibrationOffer } from '@/feeling/api';
import { createTagLabeler, SOURCE_TEXT, type TagLabeler, useFeelingTags } from '@/feeling/labels';
import type { CalibrationOffer, FeedbackAnswer, SensitivityFeeling } from '@/feeling/types';
import type { ViewingKind } from '@/neighborhood/viewingStore';
import { useRestoredAction } from '@/pending/pendingAction';
import { queryKeys } from '@/query/queryKeys';
import { generalStore, type TemperatureSensitivity } from '@/storage';
import { colors, radii, spacing } from '@/theme';

import { kstTimeText } from '../format';
import { uiStatus } from '../hourly';
import type { HomeSection, HomeView, Outfit } from '../types';
import { NOT_CURRENT_FEEDBACK, useFeelingFeedback } from '../useFeelingFeedback';

import { Card } from './WeatherSections';

type Retry = () => void | Promise<unknown>;

const GROUP_COLOR: Record<TemperatureSensitivity, string> = {
  HEAT_SENSITIVE: colors.heatSensitive,
  NORMAL: colors.normal,
  COLD_SENSITIVE: colors.coldSensitive,
};

const ANSWERS: { answer: FeedbackAnswer; label: string }[] = [
  { answer: 'RIGHT', label: '맞아요' },
  { answer: 'COLDER', label: '더 추워요' },
  { answer: 'HOTTER', label: '더 더워요' },
];

export function useHomeTagLabeler(): TagLabeler {
  const tags = useFeelingTags();
  return createTagLabeler(tags.data);
}

/** 비회원이 고른 민감도를 기기에 저장하고 홈을 다시 부른다(SCR-01 4번 선택 칩). */
function usePickGuestSensitivity() {
  const queryClient = useQueryClient();
  return async (value: TemperatureSensitivity) => {
    await generalStore.set('guestTemperatureSensitivity', value);
    await queryClient.invalidateQueries({ queryKey: queryKeys.home.all });
  };
}

// ── 3·4번: 민감도별 체감 3그룹 + 체감 피드백 ──

export function SensitivityFeelingsSection({
  home,
  viewingKind,
  isMember,
  onRetry,
}: {
  home: HomeView;
  viewingKind: ViewingKind;
  isMember: boolean;
  onRetry: Retry;
}) {
  const label = useHomeTagLabeler();
  const section = home.sensitivityFeelings;
  // 기준표로라도 항상 값이 있어야 하므로 데이터 없음도 실패와 같이 보여준다(SCR-01 빈 상태).
  const status = uiStatus(section) === 'ok' ? 'ok' : 'failed';
  const groups = section.data?.groups ?? [];
  const mine = home.personal.highlightedGroup;
  const myFeeling = mine ? (groups.find((group) => group.temperatureSensitivity === mine) ?? null) : null;

  return (
    <Card title="나 같은 사람은 지금" testID="section-sensitivity-feelings">
      <SectionState status={status} onRetry={onRetry}>
        <View style={styles.groups}>
          {SENSITIVITY_ORDER.map((group) => (
            <GroupCard
              key={group}
              group={group}
              feeling={groups.find((item) => item.temperatureSensitivity === group) ?? null}
              highlighted={group === mine}
              label={label}
            />
          ))}
        </View>
        {mine === null && !isMember ? <SensitivityChips /> : null}
        {myFeeling ? (
          <FeedbackArea
            home={home}
            shown={myFeeling}
            viewingKind={viewingKind}
            isMember={isMember}
            label={label}
          />
        ) : null}
      </SectionState>
    </Card>
  );
}

function GroupCard({
  group,
  feeling,
  highlighted,
  label,
}: {
  group: TemperatureSensitivity;
  feeling: SensitivityFeeling | null;
  highlighted: boolean;
  label: TagLabeler;
}) {
  const extra = feeling ? [label(feeling.humidityTag), label(feeling.windTag)].filter(Boolean).join(' · ') : '';
  return (
    <View
      style={[styles.group, highlighted && { borderColor: GROUP_COLOR[group], borderWidth: 2 }]}
      testID={`group-${group}`}
      accessibilityState={{ selected: highlighted }}
    >
      <AppText variant="caption" tone="secondary">
        {highlighted ? `나 · ${SENSITIVITY_LABEL[group]}` : SENSITIVITY_LABEL[group]}
      </AppText>
      <AppText variant="heading">{feeling ? label(feeling.temperatureTag) : '정보 없음'}</AppText>
      {extra ? <AppText variant="caption">{extra}</AppText> : null}
      {feeling ? (
        <AppText variant="caption" tone="secondary">
          {SOURCE_TEXT[feeling.source]}
        </AppText>
      ) : null}
    </View>
  );
}

function SensitivityChips() {
  const pick = usePickGuestSensitivity();
  return (
    <View style={styles.chipsBox} testID="sensitivity-chips">
      <AppText>나는 어느 쪽?</AppText>
      <View style={styles.chips}>
        {SENSITIVITY_ORDER.map((value) => (
          <Pressable
            key={value}
            accessibilityRole="button"
            onPress={() => pick(value).catch(() => undefined)}
            style={styles.chip}
            testID={`pick-${value}`}
          >
            <AppText variant="caption">{SENSITIVITY_LABEL[value]}</AppText>
          </Pressable>
        ))}
      </View>
    </View>
  );
}

function FeedbackArea({
  home,
  shown,
  viewingKind,
  isMember,
  label,
}: {
  home: HomeView;
  shown: SensitivityFeeling;
  viewingKind: ViewingKind;
  isMember: boolean;
  label: TagLabeler;
}) {
  const { state, submit } = useFeelingFeedback(shown, home.neighborhood.id);
  const [restoredNotice, setRestoredNotice] = useState<string | null>(null);
  // 가입 후 복귀: 자동 제출하지 않고 내 그룹 카드를 다시 보여주며 묻는다(§3 규칙 4).
  useRestoredAction('feedback', ({ action, notice }) => {
    const before = ANSWERS.find((item) => item.answer === action.payload.answer)?.label ?? '';
    setRestoredNotice(
      notice ?? `가입 전에 '${before}'를 골랐어요. 내 그룹(${SENSITIVITY_LABEL[shown.temperatureSensitivity]}) 체감을 보고 다시 답해 주세요.`,
    );
  });

  const status = home.feedbackStatus;
  const available = viewingKind === 'current' && (!isMember || status?.data?.available !== false);
  const recent = isMember ? (status?.data?.recentRecord ?? null) : null;

  return (
    <View style={styles.feedback} testID="feedback-area">
      <AppText variant="caption" tone="secondary">
        {`지금 ${label(shown.temperatureTag)}, 맞나요?`}
      </AppText>
      {restoredNotice ? (
        <AppText tone="primary" testID="feedback-restored">
          {restoredNotice}
        </AppText>
      ) : null}
      <View style={styles.answers}>
        {ANSWERS.map(({ answer, label: text }) => (
          <View key={answer} style={styles.answer}>
            <Button
              title={text}
              variant="secondary"
              disabled={!available}
              onPress={() => {
                setRestoredNotice(null);
                return submit(answer);
              }}
              testID={`feedback-${answer}`}
            />
          </View>
        ))}
      </View>
      {!available ? (
        <AppText variant="caption" tone="secondary">
          {NOT_CURRENT_FEEDBACK}
        </AppText>
      ) : null}
      {state.status === 'saved' ? (
        <AppText tone="primary" testID="feedback-saved">
          기록됐어요
        </AppText>
      ) : null}
      {available && recent ? (
        <AppText variant="caption" tone="secondary">
          다시 답하면 이전 기록을 바꿔요
        </AppText>
      ) : null}
      {state.status === 'error' ? (
        <AppText tone="danger" accessibilityRole="alert" testID="feedback-error">
          {state.message}
        </AppText>
      ) : null}
    </View>
  );
}

// ── 6번: 옷 추천 + 외출 시각 기준 ──

function outfitText(outfit: Outfit): string {
  const part = (name: string, items: string[]) => `${name} ${items.length > 0 ? items.join(', ') : '없음'}`;
  return [part('겉옷', outfit.outer), part('상의', outfit.top), part('하의', outfit.bottom), part('소품', outfit.accessories)].join(
    ' · ',
  );
}

function failedText(section: HomeSection<unknown>): string {
  return section.failure === 'PREREQUISITE_FAILED' ? '체감 정보를 불러오지 못해 보여줄 수 없어요' : '불러오지 못했어요';
}

export function OutfitSection({
  home,
  onRetry,
  onPickSensitivity,
}: {
  home: HomeView;
  onRetry: Retry;
  /** '내 민감도 고르기' → 4번 선택 칩으로 */
  onPickSensitivity: () => void;
}) {
  const label = useHomeTagLabeler();
  const { outfit, departureOutfit, personal } = home;
  const recommendation = outfit.data;
  return (
    <Card title="오늘 옷차림" testID="section-outfit">
      <AppText variant="caption" tone="secondary">
        {personal.selectionSuggested ? '보통 기준' : `${SENSITIVITY_LABEL[personal.basisGroup]} 기준`}
      </AppText>
      {personal.selectionSuggested ? (
        <Button title="내 민감도 고르기" variant="secondary" onPress={onPickSensitivity} testID="outfit-pick-sensitivity" />
      ) : null}
      <SectionState
        status={uiStatus(outfit)}
        emptyMessage="옷 추천 정보가 없어요"
        failedMessage={failedText(outfit)}
        onRetry={onRetry}
      >
        {recommendation ? (
          <View style={styles.lines}>
            <AppText>{outfitText(recommendation.outfit)}</AppText>
            {recommendation.outfit.guide ? <AppText tone="secondary">{recommendation.outfit.guide}</AppText> : null}
          </View>
        ) : null}
      </SectionState>
      {departureOutfit && departureOutfit.status !== 'EMPTY' ? (
        <View style={styles.lines} testID="departure-outfit">
          {departureOutfit.status === 'OK' && departureOutfit.data ? (
            <>
              {departureOutfit.data.departures.map((item) => (
                <AppText key={item.departureAt}>
                  {`${kstTimeText(item.departureAt) ?? ''} 외출 기준 · ${label(item.feeling.temperatureTag)}`}
                </AppText>
              ))}
              {departureOutfit.data.recommendation ? (
                <>
                  <AppText tone="secondary">{outfitText(departureOutfit.data.recommendation.recommendation.outfit)}</AppText>
                  {departureOutfit.data.recommendation.layeringGuide ? (
                    <AppText tone="secondary">{departureOutfit.data.recommendation.layeringGuide}</AppText>
                  ) : null}
                </>
              ) : null}
            </>
          ) : (
            <SectionState status="failed" failedMessage={`외출 시각 기준: ${failedText(departureOutfit)}`} onRetry={onRetry} />
          )}
        </View>
      ) : null}
    </Card>
  );
}

// ── 7번: 어제와 비교 ──

export function YesterdaySection({ home, onRetry }: { home: HomeView; onRetry: Retry }) {
  const section = home.yesterdayComparison;
  const data = section.data;
  const comparable = data?.comparable === true && Boolean(data.phrase);
  const status = uiStatus(section) === 'ok' && !comparable ? 'empty' : uiStatus(section);
  return (
    <Card title="어제와 비교" testID="section-yesterday">
      <SectionState
        status={status}
        emptyMessage="어제와 비교할 정보가 없어요"
        failedMessage={failedText(section)}
        onRetry={onRetry}
      >
        {comparable ? <AppText>{data!.phrase}</AppText> : null}
      </SectionState>
    </Card>
  );
}

// ── 민감도 보정 제안 카드(회원, 있을 때만) ──

export function CalibrationOfferCard({ section }: { section: HomeSection<CalibrationOffer> | null }) {
  const queryClient = useQueryClient();
  const [message, setMessage] = useState<string | null>(null);
  const [choosing, setChoosing] = useState(false);
  const offer = section?.status === 'OK' ? section.data : null;
  if (!offer || offer.status !== 'OFFERED') {
    return null;
  }

  const refresh = () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: queryKeys.home.all }),
      queryClient.invalidateQueries({ queryKey: queryKeys.me.profile() }),
      queryClient.invalidateQueries({ queryKey: queryKeys.me.calibrationOffer() }),
    ]);

  const run = async (call: () => Promise<unknown>) => {
    setMessage(null);
    try {
      await call();
      await refresh();
    } catch (error) {
      setMessage(isApiError(error) && error.detail ? error.detail : '저장하지 못했어요. 다시 시도해 주세요.');
      if (isApiError(error) && error.code === 'CALIBRATION_OFFER_NOT_ACTIVE') {
        refresh().catch(() => undefined);
      }
    }
  };

  return (
    <Card title="내 민감도, 이게 더 맞을까요?" testID="calibration-offer">
      <AppText>{`최근 기록을 보면 '${SENSITIVITY_LABEL[offer.suggestedGroup]}' 그룹에 더 가까워요`}</AppText>
      <AppText variant="caption" tone="secondary">
        {`지금은 '${SENSITIVITY_LABEL[offer.currentGroup]}' · 최근 기록 ${offer.recordCount}건 기준 · 이전 체감 기록은 그대로 남아요`}
      </AppText>
      <Button
        title={`'${SENSITIVITY_LABEL[offer.suggestedGroup]}'으로 바꾸기`}
        onPress={() => run(() => acceptCalibrationOffer(api, offer.offerId))}
        testID="calibration-accept"
      />
      <Button
        title="지금대로 둘게요"
        variant="secondary"
        onPress={() => run(() => declineCalibrationOffer(api, offer.offerId))}
        testID="calibration-decline"
      />
      <Pressable accessibilityRole="button" onPress={() => setChoosing((value) => !value)}>
        <AppText tone="secondary">직접 고르기</AppText>
      </Pressable>
      {choosing ? (
        <View style={styles.chips}>
          {SENSITIVITY_ORDER.map((value) => (
            <View key={value} style={styles.answer}>
              <Button
                title={SENSITIVITY_LABEL[value]}
                variant="secondary"
                disabled={value === offer.currentGroup}
                onPress={() => run(() => changeTemperatureSensitivity(api, value, 'DIRECT'))}
                testID={`sensitivity-change-${value}`}
              />
            </View>
          ))}
        </View>
      ) : null}
      {message ? (
        <AppText tone="danger" accessibilityRole="alert">
          {message}
        </AppText>
      ) : null}
    </Card>
  );
}

const styles = StyleSheet.create({
  groups: { flexDirection: 'row', gap: spacing.sm },
  group: {
    flex: 1,
    gap: 2,
    padding: spacing.sm,
    borderRadius: radii.md,
    borderWidth: 1,
    borderColor: colors.border,
    backgroundColor: colors.surface,
  },
  chipsBox: { gap: spacing.sm, marginTop: spacing.sm },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm },
  chip: {
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    borderRadius: radii.pill,
    borderWidth: 1,
    borderColor: colors.primary,
  },
  feedback: { gap: spacing.sm, marginTop: spacing.md },
  answers: { flexDirection: 'row', gap: spacing.sm },
  answer: { flex: 1 },
  lines: { gap: spacing.xs },
});
