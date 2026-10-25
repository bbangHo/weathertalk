// SCR-01 날씨 섹션: 현재 날씨·시간별 예보·중기 예보와 출처 표시 (07 SCR-01 2·9·10, §1.4, owner-todos §3 출처 표시)
import type { ReactNode } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';

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
  skyIcon,
  skyText,
  temperatureText,
  windText,
} from '../format';
import { buildHourlyItems, uiStatus } from '../hourly';
import type { CurrentObservation, DailyForecast, HomeSection, HourlyForecast } from '../types';

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

function CurrentWeatherBody({ observation }: { observation: CurrentObservation }) {
  const air = observation.airQuality;
  const uv = observation.uv;
  const airTime = kstTimeText(air?.measuredAt ?? null);
  const airFreshness = air ? freshnessText(air.freshness) : null;
  return (
    <View style={styles.body}>
      <View style={styles.hero}>
        <AppText style={styles.heroIcon}>{skyIcon(observation.sky, observation.dayNight)}</AppText>
        <View>
          <AppText style={styles.heroTemp}>{temperatureText(observation.temperature)}</AppText>
          <AppText tone="secondary">{skyText(observation.sky, observation.dayNight)}</AppText>
        </View>
      </View>
      <AppText variant="caption" tone="secondary" testID="observation-time">
        {observationTimeText(observation)}
      </AppText>
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
              <AppText>{skyIcon(item.slot.sky, item.slot.dayNight)}</AppText>
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
              <View key={day.date} style={styles.row} testID="daily-day">
                <AppText>{dateLabel(day.date)}</AppText>
                {day.source === null ? (
                  <AppText tone="secondary">{NO_DATA}</AppText>
                ) : (
                  <AppText>
                    {`${skyText(day.amSky)}/${skyText(day.pmSky)}  ${temperatureText(day.minTemperature, 0)} / ${temperatureText(day.maxTemperature, 0)}`}
                  </AppText>
                )}
              </View>
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
    borderWidth: 1,
    borderColor: colors.border,
    padding: spacing.lg,
    gap: spacing.md,
  },
  cardEmphasized: { borderColor: colors.primary, borderWidth: 2 },
  body: { gap: spacing.sm },
  hero: { flexDirection: 'row', alignItems: 'center', gap: spacing.lg },
  heroIcon: { fontSize: 44, lineHeight: 52 },
  heroTemp: { fontSize: 40, lineHeight: 48, fontWeight: '700' },
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
  attribution: { textAlign: 'center' },
});
