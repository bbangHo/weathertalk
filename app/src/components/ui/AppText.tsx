import { StyleSheet, Text, type TextProps } from 'react-native';

import { colors, typography, type TypographyVariant } from '@/theme';

type Tone = 'primary' | 'secondary' | 'danger' | 'onPrimary' | 'disabled';

const TONE_COLOR: Record<Tone, string> = {
  primary: colors.textPrimary,
  secondary: colors.textSecondary,
  danger: colors.danger,
  onPrimary: colors.onPrimary,
  disabled: colors.textDisabled,
};

export type AppTextProps = TextProps & {
  variant?: TypographyVariant;
  tone?: Tone;
};

export function AppText({ variant = 'body', tone = 'primary', style, ...rest }: AppTextProps) {
  return <Text {...rest} style={[styles[variant], { color: TONE_COLOR[tone] }, style]} />;
}

const styles = StyleSheet.create(typography);
