// A0 자리표시 화면. A1~A5에서 실제 화면으로 바꾼다.
import { Link, type Href } from 'expo-router';
import { StyleSheet, View } from 'react-native';

import { colors, radii, spacing } from '@/theme';

import { AppText } from './AppText';
import { Screen } from './Screen';

export type PlaceholderLink = { label: string; href: Href };

export type PlaceholderScreenProps = {
  code: string;
  title: string;
  description: string;
  links?: PlaceholderLink[];
};

export function PlaceholderScreen({ code, title, description, links = [] }: PlaceholderScreenProps) {
  return (
    <Screen>
      <AppText variant="caption" tone="secondary">
        {code}
      </AppText>
      <AppText variant="title">{title}</AppText>
      <AppText tone="secondary">{description}</AppText>
      {links.length > 0 ? (
        <View style={styles.links}>
          {links.map((link) => (
            <Link key={link.label} href={link.href} style={styles.link}>
              <AppText tone="primary">{link.label} →</AppText>
            </Link>
          ))}
        </View>
      ) : null}
    </Screen>
  );
}

const styles = StyleSheet.create({
  links: { gap: spacing.sm, marginTop: spacing.md },
  link: {
    backgroundColor: colors.surface,
    borderRadius: radii.md,
    padding: spacing.md,
  },
});
