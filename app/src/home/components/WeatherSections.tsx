// SCR-01 날씨 섹션: 현재 날씨·시간별 예보·중기 예보와 출처 표시 (07 SCR-01 2·10·11, §1.4, owner-todos §3 출처 표시)
import Ionicons from '@expo/vector-icons/Ionicons';
import { useState, type ReactNode } from 'react';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';

import { AppText, SectionState } from '@/components/ui';
import { colors, radii, spacing } from '@/theme';

import {
  airGradeText,
  dateLabel,
  freshnessText,
  kstTimeText,
  NO_DATA,
  percentText,
  precipitationTypeText,
  rawNumberText,
  skyText,
  temperatureText,
  windText,
} from '../format';
import { buildHourlyItems, uiStatus } from '../hourly';
import type { CurrentObservation, DailyForecast, DailyForecastDay, HomeSection, HourlyForecast } from '../types';
import { WeatherIcon } from './WeatherIcon';

type SectionProps<T> = { section: HomeSection<T>; onRetry: () => void | Promise<unknown> };

export function Card({
  title,
  children,
  testID,
  emphasized = false,
}: {
  title: string;
  children: ReactNode;
  testID?: string;
  /** 알림으로 들어와 이 섹션을 가리킬 때(07 §7.3) */
  emphasized?: boolean;
}) {
  return (
    <View
      style={[styles.card, emphasized && styles.cardEmphasized]}
      testID={testID}
      accessibilityState={emphasized ? { selected: true } : undefined}
    >
      <AppText variant="heading">{title}</AppText>
      {children}
    </View>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <View style={styles.row}>
      <AppText tone="secondary">{label}</AppText>
      <AppText>{value}</AppText>
    </View>
  );
}

function observationTimeText(observation: CurrentObservation): string {
  const time = kstTimeText(observation.observedAt);
  const usesForecast = Object.values(observation.sources).includes('FORECAST');
  const parts = [time ? `${time} 기준` : '관측 시각 정보 없음'];
  const freshness = freshnessText(observation.weatherFreshness);
  if (freshness) {
    parts.push(freshness);
  }
  if (usesForecast) {
    parts.push('일부 값은 예보예요');
  }
  return parts.join(' · ');
}

function precipitationText(observation: CurrentObservation): string {
  if (observation.precipitationPhrase) {
    return observation.precipitationPhrase;
  }
  const type = observation.precipitationType;
  const amount = observation.precipitation?.label ?? null;
  if (type === null || type === 'NONE') {
    return amount ?? (type === 'NONE' ? '없음' : NO_DATA);
  }
  return amount ? `${precipitationTypeText(type)} ${amount}` : precipitationTypeText(type);
}

export function CurrentWeatherSection({ section, onRetry }: SectionProps<CurrentObservation>) {
  const observation = section.data;
  return (
    <Card title="지금 날씨" testID="section-current-weather">
      <SectionState status={uiStatus(section)} emptyMessage="지금 날씨 정보가 없어요" onRetry={onRetry}>
        {observation ? <CurrentWeatherBody observation={observation} /> : null}
      </SectionState>
    </Card>
  );
}

/** 기온·하늘·요약 한 줄·관측 시각만 먼저 보이고, 나머지 수치와 대기질 출처는 '자세히'를 눌러 펼친다(SCR-01 2). */
function CurrentWeatherBody({ observation }: { observation: CurrentObservation }) {
  const [expanded, setExpanded] = useState(false);
  return (
    <View style={styles.body}>
      <View style={styles.hero}>
        <WeatherIcon
          sky={observation.sky}
          dayNight={observation.dayNight}
          precipitationType={observation.precipitationType}
          size={48}
          testID="current-weather-icon"
        />
        <View style={styles.heroText}>
          <View style={styles.heroLine}>
            <AppText variant="display">{temperatureText(observation.temperature)}</AppText>
            <AppText tone="secondary">{skyText(observation.sky, observation.dayNight)}</AppText>
          </View>
          <AppText variant="caption" tone="secondary">
            {`체감 ${temperatureText(observation.feelsLike)} · ${observation.precipitationPhrase ?? `강수 ${precipitationText(observation)}`}`}
          </AppText>
        </View>
      </View>
      <AppText variant="caption" tone="secondary" testID="observation-time">
        {observationTimeText(observation)}
      </AppText>
      {expanded ? <CurrentWeatherDetails observation={observation} /> : null}
      <Pressable
        testID="current-weather-toggle"
        accessibilityRole="button"
        accessibilityState={{ expanded }}
        onPress={() => setExpanded((value) => !value)}
        style={({ pressed }) => [styles.toggle, pressed && styles.togglePressed]}
      >
        <AppText variant="button" tone="secondary">
          {expanded ? '접기' : '습도·바람·자외선·미세먼지 자세히'}
        </AppText>
        <Ionicons name={expanded ? 'chevron-up' : 'chevron-down'} size={18} color={colors.textSecondary} />
      </Pressable>
    </View>
  );
}

function CurrentWeatherDetails({ observation }: { observation: CurrentObservation }) {
  const air = observation.airQuality;
  const uv = observation.uv;
  const airTime = kstTimeText(air?.measuredAt ?? null);
  const airFreshness = air ? freshnessText(air.freshness) : null;
  return (
    <View style={styles.body} testID="current-weather-details">
      <View style={styles.divider} />
      <Row label="체감온도" value={temperatureText(observation.feelsLike)} />
      <Row label="강수" value={precipitationText(observation)} />
      <Row label="습도" value={percentText(observation.humidity)} />
      <Row label="바람" value={windText(observation.windSpeed)} />

      <View style={styles.divider} />
      <Row label="자외선" value={uv ? `${uv.gradeLabel}${uv.value !== null ? ` (${uv.value})` : ''}` : NO_DATA} />
      {uv?.guidance ? (
        <AppText variant="caption" tone="secondary">
          {uv.guidance}
        </AppText>
      ) : null}

      <View style={styles.divider} />
      <Row label="미세먼지" value={`${airGradeText(air?.pm10Grade)} · ${rawNumberText(air?.pm10, '㎍/㎥')}`} />
      <Row label="초미세먼지" value={`${airGradeText(air?.pm25Grade)} · ${rawNumberText(air?.pm25, '㎍/㎥')}`} />
      <AppText variant="caption" tone="secondary">
        {[air?.stationName ? `${air.stationName} 측정소` : null, airTime ? `${airTime} 측정` : null, airFreshness]
          .filter(Boolean)
          .join(' · ') || '측정 정보 없음'}
      </AppText>
      <AppText variant="caption" tone="secondary" testID="air-attribution">
        자료: 환경부/한국환경공단 에어코리아 · 실시간 자료라 오류가 있을 수 있어요
      </AppText>
    </View>
  );
}

export function HourlyForecastSection({
  section,
  onRetry,
  emphasized = false,
}: SectionProps<HourlyForecast> & { emphasized?: boolean }) {
  const forecast = section.data;
  return (
    <Card title="시간별 예보" testID="section-hourly" emphasized={emphasized}>
      <SectionState status={uiStatus(section)} emptyMessage="시간별 예보가 아직 없어요" onRetry={onRetry}>
        {forecast ? <HourlyBody forecast={forecast} emphasized={emphasized} /> : null}
      </SectionState>
    </Card>
  );
}

/** 강수 예보 알림으로 들어오면 weather가 준 다음 강수 시작 칸을 강조한다(07 §7.3). 시작 시각은 판정하지 않고 받은 값과 같은 칸만 찾는다. */
function HourlyBody({ forecast, emphasized }: { forecast: HourlyForecast; emphasized: boolean }) {
  const items = buildHourlyItems(forecast.slots);
  const next = emphasized ? forecast.nextPrecipitation : null;
  const startsAt = next ? Date.parse(next.startsAt) : NaN;
  const issued = kstTimeText(forecast.issuedAt);
  const freshness = freshnessText(forecast.freshness);
  return (
    <View style={styles.body}>
      <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.hourlyList}>
        {items.map((item) =>
          item.kind === 'date' ? (
            <View key={item.key} style={styles.dateMarker} testID="hourly-date-marker">
              <AppText variant="caption" tone="secondary">
                {dateLabel(item.date)}
              </AppText>
            </View>
          ) : (
            <View
              key={item.key}
              style={[styles.hourlySlot, Date.parse(item.slot.forecastAt) === startsAt && styles.hourlySlotEmphasized]}
              testID={Date.parse(item.slot.forecastAt) === startsAt ? 'hourly-slot-precipitation-start' : 'hourly-slot'}
            >
              <AppText variant="caption" tone="secondary">
                {`${item.slot.hour}시`}
              </AppText>
              <WeatherIcon
                sky={item.slot.sky}
                dayNight={item.slot.dayNight}
                precipitationType={item.slot.precipitationType}
              />
              <AppText>{temperatureText(item.slot.temperature, 0)}</AppText>
              <AppText variant="caption" tone="secondary">
                {item.slot.precipitationProbability === null ? '-' : `${item.slot.precipitationProbability}%`}
              </AppText>
            </View>
          ),
        )}
      </ScrollView>
      {next ? (
        <AppText testID="hourly-next-precipitation">
          {`${kstTimeText(next.startsAt) ?? ''}부터 ${next.phrase ?? precipitationTypeText(next.type)} 예상`}
        </AppText>
      ) : null}
      <AppText variant="caption" tone="secondary">
        {[issued ? `${issued} 발표` : null, freshness].filter(Boolean).join(' · ') || ' '}
      </AppText>
    </View>
  );
}

export function DailyForecastSection({ section, onRetry }: SectionProps<DailyForecast>) {
  const forecast = section.data;
  return (
    <Card title="주간 예보" testID="section-daily">
      <SectionState status={uiStatus(section)} emptyMessage="주간 예보가 아직 없어요" onRetry={onRetry}>
        {forecast ? (
          <View style={styles.body}>
            {forecast.days.map((day) => (
              <DailyRow key={day.date} day={day} />
            ))}
            {freshnessText(forecast.freshness) ? (
              <AppText variant="caption" tone="secondary">
                {freshnessText(forecast.freshness)}
              </AppText>
            ) : null}
          </View>
        ) : null}
      </SectionState>
    </Card>
  );
}

/** 주간 예보 한 줄: 날짜 | 오전·오후 하늘 | 최저 / 최고 */
function DailyRow({ day }: { day: DailyForecastDay }) {
  return (
    <View style={styles.dailyRow} testID="daily-day">
      <AppText style={styles.dailyDate}>{dateLabel(day.date)}</AppText>
      {day.source === null ? (
        <AppText tone="secondary">{NO_DATA}</AppText>
      ) : (
        <>
          <View style={styles.dailyIcons}>
            <WeatherIcon sky={day.amSky} precipitationType={day.amPrecipitationType} labelPrefix="오전" />
            <WeatherIcon sky={day.pmSky} precipitationType={day.pmPrecipitationType} labelPrefix="오후" />
          </View>
          <View style={styles.dailyTemps}>
            <AppText tone="secondary" style={styles.dailyTemp}>
              {temperatureText(day.minTemperature, 0)}
            </AppText>
            <AppText tone="secondary">/</AppText>
            <AppText style={styles.dailyTemp}>{temperatureText(day.maxTemperature, 0)}</AppText>
          </View>
        </>
      )}
    </View>
  );
}

/** 기상청 공공누리 제1유형 출처 표시 */
export function WeatherAttribution() {
  return (
    <AppText variant="caption" tone="secondary" style={styles.attribution} testID="kma-attribution">
      날씨 자료: 기상청
    </AppText>
  );
}

const styles = StyleSheet.create({
  card: {
    backgroundColor: colors.surface,
    borderRadius: radii.lg,
    padding: spacing.card,
    gap: spacing.md,
  },
  cardEmphasized: { borderColor: colors.primary, borderWidth: 2 },
  body: { gap: spacing.sm },
  hero: { flexDirection: 'row', alignItems: 'center', gap: spacing.lg },
  heroText: { flex: 1 },
  heroLine: { flexDirection: 'row', alignItems: 'baseline', gap: spacing.sm },
  toggle: {
    minHeight: 44,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: spacing.xs,
    borderRadius: radii.md,
    backgroundColor: colors.background,
  },
  togglePressed: { backgroundColor: colors.surfaceMuted },
  row: { flexDirection: 'row', justifyContent: 'space-between', gap: spacing.md },
  divider: { height: 1, backgroundColor: colors.border, marginVertical: spacing.xs },
  hourlyList: { gap: spacing.md, alignItems: 'stretch' },
  hourlySlot: { alignItems: 'center', gap: spacing.xs, minWidth: 44 },
  hourlySlotEmphasized: { borderRadius: radii.md, borderWidth: 2, borderColor: colors.primary, paddingHorizontal: spacing.xs },
  dateMarker: {
    justifyContent: 'center',
    paddingHorizontal: spacing.sm,
    borderLeftWidth: 1,
    borderLeftColor: colors.border,
  },
  dailyRow: { flexDirection: 'row', alignItems: 'center', gap: spacing.md, minHeight: 32 },
  dailyDate: { flex: 1 },
  dailyIcons: { flexDirection: 'row', gap: spacing.xs },
  dailyTemps: { flexDirection: 'row', justifyContent: 'flex-end', gap: spacing.xs, width: 80 },
  dailyTemp: { minWidth: 32, textAlign: 'right' },
  attribution: { textAlign: 'center' },
});
