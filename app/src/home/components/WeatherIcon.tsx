// 하늘·강수 아이콘. 받은 하늘·강수형태·낮밤 값을 그림으로만 바꾼다(판정하지 않음, 07 §1.5).
import Ionicons from '@expo/vector-icons/Ionicons';
import type { ComponentProps } from 'react';

import { colors } from '@/theme';

import { precipitationTypeText, skyText } from '../format';
import type { DayNight, PrecipitationType, SkyCondition } from '../types';

type IconName = ComponentProps<typeof Ionicons>['name'];

/** 강수가 있으면 강수 아이콘이 하늘보다 앞선다. 둘 다 없으면 '정보 없음' 아이콘. */
export function weatherIconOf(
  sky: SkyCondition | null,
  dayNight: DayNight | null,
  precipitationType: PrecipitationType | null = null,
): { name: IconName; color: string } {
  if (precipitationType === 'SNOW') {
    return { name: 'snow', color: colors.weatherSnow };
  }
  if (precipitationType && precipitationType !== 'NONE') {
    return { name: 'rainy', color: colors.weatherRain };
  }
  const night = dayNight === 'NIGHT';
  switch (sky) {
    case 'CLEAR':
      return night ? { name: 'moon', color: colors.weatherMoon } : { name: 'sunny', color: colors.weatherSun };
    case 'MOSTLY_CLOUDY':
      return night ? { name: 'cloudy-night', color: colors.weatherMoon } : { name: 'partly-sunny', color: colors.weatherSun };
    case 'OVERCAST':
      return { name: 'cloudy', color: colors.weatherCloud };
    default:
      return { name: 'cloud-offline-outline', color: colors.textDisabled };
  }
}

export type WeatherIconProps = {
  sky: SkyCondition | null;
  dayNight?: DayNight | null;
  precipitationType?: PrecipitationType | null;
  size?: number;
  /** 화면 읽기 프로그램용 앞말(예: '오전') */
  labelPrefix?: string;
  testID?: string;
};

export function WeatherIcon({
  sky,
  dayNight = null,
  precipitationType = null,
  size = 24,
  labelPrefix,
  testID,
}: WeatherIconProps) {
  const { name, color } = weatherIconOf(sky, dayNight, precipitationType);
  const precipitating = precipitationType !== null && precipitationType !== 'NONE';
  const label = precipitating ? precipitationTypeText(precipitationType) : skyText(sky, dayNight);
  return (
    <Ionicons
      testID={testID}
      name={name}
      size={size}
      color={color}
      accessibilityRole="image"
      accessibilityLabel={labelPrefix ? `${labelPrefix} ${label}` : label}
    />
  );
}
