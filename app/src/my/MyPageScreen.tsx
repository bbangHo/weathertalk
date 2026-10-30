// SCR-09 마이페이지 (07 SCR-09, §3.1 마이페이지 행, §6.1·6.2, 01 FR-MEM-07·08·09·10·11, 04 FR-FEL-08·11)
// - 회원: 닉네임·민감도·로그인 계정·보정 제안·내가 쓴 글·내 체감 기록·외출 시각(A5)·메뉴·로그아웃·탈퇴
// - 비회원: 가입 카드 + 기기 설정(기기 민감도·관심 동네·알림 구독·약관·버전)
// - 회원 정보 조회 실패는 기본값으로 채우지 않고 오류 + 다시 시도. 서버 거부 사유는 받은 문구 그대로 보여준다.
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { router } from 'expo-router';
import { type ReactNode, useEffect, useState } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, TextInput, View } from 'react-native';

import { api } from '@/api';
import { ErrorCodes, isApiError } from '@/api/errors';
import {
  type AuthProvider,
  changeTemperatureSensitivity,
  getMyProfile,
  type MyProfile,
  SENSITIVITY_LABEL,
  SENSITIVITY_ORDER,
} from '@/auth/api';
import { AppText, Button, Screen, SectionState } from '@/components/ui';
import { fetchMyPosts } from '@/community/api';
import { codePointLength } from '@/community/format';
import { getAppParams } from '@/config/appParams';
import { PRIVACY_POLICY_URL, TERMS_OF_SERVICE_URL } from '@/config/env';
import { CalibrationOfferCard } from '@/home/components/FeelingSections';
import { routes } from '@/navigation/routes';
import { writeGate } from '@/pending/writeGate';
import { Alert } from '@/platform/alert';
import { PUSH_NOTIFICATIONS_ENABLED } from '@/platform/features';
import { openExternalLink } from '@/platform/openExternalLink';
import { queryKeys } from '@/query/queryKeys';
import { useSession } from '@/session/useSession';
import { generalStore, type TemperatureSensitivity } from '@/storage';
import { colors, radii, spacing } from '@/theme';

import { changeNickname, getCalibrationOffer, getMyFeelingRecords, unlinkLoginAccount } from './api';
import { appVersionText } from './appInfo';
import { FeelingPatternView, FeelingRecordRow, MyPostRow } from './components';
import {
  LOGOUT_CONFIRM,
  performLogout,
  performWithdraw,
  type SignOutDeps,
  WITHDRAW_DONE,
  WITHDRAW_EXPLAIN,
} from './signOut';

const PROVIDER_LABEL: Record<AuthProvider | 'DEV', string> = {
  KAKAO: '카카오',
  APPLE: '애플',
  GOOGLE: '구글',
  TOSS_ANON: '토스',
  DEV: '개발용 계정',
};

const KST_OFFSET_MS = 9 * 60 * 60 * 1000;

/** "2026년 11월 14일" (한국 날짜) */
export function kstFullDate(iso: string): string {
  const ms = Date.parse(iso);
  if (!Number.isFinite(ms)) {
    return '';
  }
  const kst = new Date(ms + KST_OFFSET_MS);
  return `${kst.getUTCFullYear()}년 ${kst.getUTCMonth() + 1}월 ${kst.getUTCDate()}일`;
}

function errorText(error: unknown, fallback: string): string {
  return isApiError(error) && error.detail ? error.detail : fallback;
}

export function useMyFeelingRecords(enabled: boolean) {
  return useQuery({
    queryKey: queryKeys.me.feelingRecords(),
    queryFn: () => getMyFeelingRecords(api),
    enabled,
  });
}

export type MyPageScreenProps = {
  /** 테스트에서 로그아웃·탈퇴 순서를 바꿀 때 */
  signOutDeps?: SignOutDeps;
};

export function MyPageScreen({ signOutDeps }: MyPageScreenProps) {
  const session = useSession();
  if (session.status === 'restoring') {
    return <ActivityIndicator style={styles.loading} color={colors.primary} />;
  }
  return session.status === 'member' ? <MemberMyPage signOutDeps={signOutDeps} /> : <GuestMyPage />;
}

// ── 비회원 ──

function GuestMyPage() {
  return (
    <Screen>
      <Card testID="guest-signup-card">
        <AppText variant="heading">가입하고 함께 남겨 보세요</AppText>
        <AppText tone="secondary">체감 기록·현장 제보·글·댓글은 가입한 뒤 남길 수 있어요. 가입은 1분이면 끝나요.</AppText>
        <Button title="가입하기" onPress={() => writeGate.openSignup()} testID="guest-signup" />
      </Card>
      <Card>
        <AppText variant="heading">이 기기 설정</AppText>
        <GuestSensitivity />
      </Card>
      <Card>
        <AppText variant="heading">외출 시각</AppText>
        <AppText tone="secondary">외출 시각 설정은 곧 열려요</AppText>
      </Card>
      <Menu />
    </Screen>
  );
}

function GuestSensitivity() {
  const queryClient = useQueryClient();
  const [value, setValue] = useState<TemperatureSensitivity | null>(null);
  useEffect(() => {
    generalStore
      .get('guestTemperatureSensitivity')
      .then(setValue)
      .catch(() => undefined);
  }, []);
  const pick = async (next: TemperatureSensitivity) => {
    setValue(next);
    await generalStore.set('guestTemperatureSensitivity', next).catch(() => undefined);
    await queryClient.invalidateQueries({ queryKey: queryKeys.home.all });
  };
  return (
    <View style={styles.block} testID="guest-sensitivity">
      <AppText>내 온도 민감도(이 기기)</AppText>
      <SensitivityChoices value={value} onPick={pick} />
    </View>
  );
}

// ── 회원 ──

function MemberMyPage({ signOutDeps }: { signOutDeps?: SignOutDeps }) {
  const profile = useQuery({ queryKey: queryKeys.me.profile(), queryFn: () => getMyProfile(api) });

  if (profile.isPending) {
    return <ActivityIndicator style={styles.loading} color={colors.primary} />;
  }
  if (profile.isError || !profile.data) {
    return (
      <Screen>
        <SectionState
          status="failed"
          failedMessage="회원 정보를 불러오지 못했어요"
          onRetry={() => profile.refetch()}
        />
        <Menu />
      </Screen>
    );
  }
  const me = profile.data;
  return (
    <Screen>
      <Card testID="profile-card">
        <NicknameEditor profile={me} />
        <MemberSensitivity profile={me} />
      </Card>
      <MyCalibrationOffer />
      <MyPostsPreview />
      <MyFeelingPreview />
      <Card>
        <AppText variant="heading">외출 시각(이 기기)</AppText>
        <AppText tone="secondary">외출 시각 설정은 곧 열려요</AppText>
      </Card>
      <LoginAccounts profile={me} />
      <Menu />
      <AccountActions signOutDeps={signOutDeps} />
    </Screen>
  );
}

function NicknameEditor({ profile }: { profile: MyProfile }) {
  const queryClient = useQueryClient();
  const { nicknameMinLength: min, nicknameMaxLength: max } = getAppParams();
  const [editing, setEditing] = useState(false);
  const [value, setValue] = useState(profile.nickname);
  const [message, setMessage] = useState<{ text: string; error: boolean } | null>(null);
  const availableAt = profile.nicknameChangeAvailableAt;
  const [openedAt] = useState(() => Date.now());
  const locked = availableAt !== null && Date.parse(availableAt) > openedAt;
  const length = codePointLength(value.trim());
  const valid = length >= min && length <= max && value.trim() !== profile.nickname;

  const save = async () => {
    setMessage(null);
    try {
      const result = await changeNickname(api, value.trim());
      queryClient.setQueryData<MyProfile>(queryKeys.me.profile(), (prev) =>
        prev ? { ...prev, nickname: result.nickname, nicknameChangeAvailableAt: result.nextChangeAvailableAt } : prev,
      );
      // 글·댓글 작성자 표시가 현재 닉네임이라 목록도 다시 부른다(BR-COM-18).
      queryClient.invalidateQueries({ queryKey: queryKeys.community.all }).catch(() => undefined);
      setEditing(false);
      setMessage({
        text: result.nextChangeAvailableAt
          ? `닉네임을 바꿨어요. 다음 변경은 ${kstFullDate(result.nextChangeAvailableAt)}부터 할 수 있어요`
          : '닉네임을 바꿨어요',
        error: false,
      });
    } catch (error) {
      let text = errorText(error, '닉네임을 바꾸지 못했어요. 다시 시도해 주세요.');
      const next = isApiError(error) ? error.properties.nextChangeAvailableAt : null;
      if (isApiError(error) && error.code === 'NICKNAME_CHANGE_TOO_SOON' && typeof next === 'string') {
        text = `${text} ${kstFullDate(next)}부터 바꿀 수 있어요.`;
      }
      setMessage({ text, error: true });
    }
  };

  return (
    <View style={styles.block}>
      <AppText variant="caption" tone="secondary">
        닉네임
      </AppText>
      {editing ? (
        <>
          <TextInput
            style={styles.input}
            value={value}
            onChangeText={setValue}
            maxLength={max * 2}
            autoFocus
            testID="nickname-input"
          />
          <AppText variant="caption" tone="secondary">{`${min}~${max}자 한글·영문·숫자 · 30일에 한 번 바꿀 수 있어요`}</AppText>
          <View style={styles.row}>
            <View style={styles.flex}>
              <Button title="저장" onPress={save} disabled={!valid} testID="nickname-save" />
            </View>
            <View style={styles.flex}>
              <Button
                title="취소"
                variant="secondary"
                onPress={() => {
                  setEditing(false);
                  setValue(profile.nickname);
                }}
              />
            </View>
          </View>
        </>
      ) : (
        <View style={styles.row}>
          <AppText variant="title" testID="nickname">
            {profile.nickname}
          </AppText>
          <Pressable
            accessibilityRole="button"
            accessibilityState={{ disabled: locked }}
            disabled={locked}
            onPress={() => {
              setValue(profile.nickname);
              setEditing(true);
              setMessage(null);
            }}
            testID="nickname-edit"
          >
            <AppText tone={locked ? 'disabled' : 'primary'}>바꾸기</AppText>
          </Pressable>
        </View>
      )}
      {locked && !editing ? (
        <AppText variant="caption" tone="secondary" testID="nickname-next">
          {`${kstFullDate(availableAt!)}부터 바꿀 수 있어요`}
        </AppText>
      ) : null}
      {message ? (
        <AppText
          variant="caption"
          tone={message.error ? 'danger' : 'secondary'}
          accessibilityRole={message.error ? 'alert' : undefined}
          testID="nickname-message"
        >
          {message.text}
        </AppText>
      ) : null}
    </View>
  );
}

function MemberSensitivity({ profile }: { profile: MyProfile }) {
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const pick = async (next: TemperatureSensitivity) => {
    if (next === profile.temperatureSensitivity || saving) {
      return;
    }
    setError(null);
    setSaving(true);
    try {
      const updated = await changeTemperatureSensitivity(api, next, 'DIRECT');
      queryClient.setQueryData(queryKeys.me.profile(), updated);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.home.all }),
        queryClient.invalidateQueries({ queryKey: queryKeys.me.calibrationOffer() }),
      ]);
    } catch (cause) {
      setError(errorText(cause, '민감도를 바꾸지 못했어요. 다시 시도해 주세요.'));
    } finally {
      setSaving(false);
    }
  };
  return (
    <View style={styles.block} testID="member-sensitivity">
      <AppText variant="caption" tone="secondary">
        온도 민감도
      </AppText>
      <SensitivityChoices value={profile.temperatureSensitivity} onPick={pick} disabled={saving} />
      <AppText variant="caption" tone="secondary">
        바꿔도 이전 체감 기록은 그대로 남아요
      </AppText>
      {error ? (
        <AppText variant="caption" tone="danger" accessibilityRole="alert">
          {error}
        </AppText>
      ) : null}
    </View>
  );
}

function SensitivityChoices({
  value,
  onPick,
  disabled = false,
}: {
  value: TemperatureSensitivity | null;
  onPick: (value: TemperatureSensitivity) => void;
  disabled?: boolean;
}) {
  return (
    <View style={styles.chips}>
      {SENSITIVITY_ORDER.map((option) => {
        const active = option === value;
        return (
          <Pressable
            key={option}
            accessibilityRole="radio"
            accessibilityState={{ selected: active, disabled }}
            disabled={disabled}
            onPress={() => onPick(option)}
            style={[styles.chip, active && styles.chipActive]}
            testID={`sensitivity-${option}`}
          >
            <AppText tone={active ? 'onPrimary' : 'primary'}>{SENSITIVITY_LABEL[option]}</AppText>
          </Pressable>
        );
      })}
    </View>
  );
}

function MyCalibrationOffer() {
  const offer = useQuery({ queryKey: queryKeys.me.calibrationOffer(), queryFn: () => getCalibrationOffer(api) });
  // 보정 제안은 있을 때만 보이는 부가 카드라 조회 실패는 조용히 넘긴다.
  if (!offer.data?.offer) {
    return null;
  }
  return <CalibrationOfferCard section={{ status: 'OK', data: offer.data.offer, failure: null }} />;
}

function MyPostsPreview() {
  const size = getAppParams().myPagePreviewCount;
  const posts = useQuery({
    queryKey: [...queryKeys.me.posts(), 'preview'],
    queryFn: () => fetchMyPosts(api, null, size),
  });
  const items = posts.data?.items ?? [];
  return (
    <Card testID="my-posts-preview">
      <SectionHeader title="내가 쓴 글" onMore={() => router.push(routes.myPosts())} moreTestID="my-posts-more" />
      {posts.isPending ? <ActivityIndicator color={colors.primary} /> : null}
      {posts.isError ? (
        <SectionState status="failed" failedMessage="글을 불러오지 못했어요" onRetry={() => posts.refetch()} />
      ) : null}
      {posts.data && items.length === 0 ? <AppText tone="secondary">아직 쓴 글이 없어요</AppText> : null}
      {items.slice(0, size).map((post) => (
        <MyPostRow key={post.postId} post={post} onOpen={() => router.push(routes.postDetail(post.postId))} />
      ))}
    </Card>
  );
}

function MyFeelingPreview() {
  const size = getAppParams().myPagePreviewCount;
  const records = useMyFeelingRecords(true);
  const items = records.data?.records ?? [];
  return (
    <Card testID="my-feeling-preview">
      <SectionHeader
        title="내 체감 기록"
        onMore={() => router.push(routes.myFeelingRecords())}
        moreTestID="my-feeling-more"
      />
      {records.isPending ? <ActivityIndicator color={colors.primary} /> : null}
      {records.isError ? (
        <SectionState status="failed" failedMessage="기록을 불러오지 못했어요" onRetry={() => records.refetch()} />
      ) : null}
      {records.data ? <FeelingPatternView pattern={records.data.pattern} /> : null}
      {records.data && items.length === 0 ? (
        <AppText tone="secondary">최근 30일 동안 남긴 기록이 없어요</AppText>
      ) : null}
      {items.slice(0, size).map((record) => (
        <FeelingRecordRow key={record.id.value} record={record} />
      ))}
    </Card>
  );
}

function LoginAccounts({ profile }: { profile: MyProfile }) {
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const unlink = (provider: AuthProvider | 'DEV') =>
    Alert.alert(`${PROVIDER_LABEL[provider]} 연결을 해제할까요?`, '해제하면 이 계정으로는 로그인할 수 없어요.', [
      { text: '취소', style: 'cancel' },
      {
        text: '해제',
        style: 'destructive',
        onPress: async () => {
          setError(null);
          try {
            const updated = await unlinkLoginAccount(api, provider);
            queryClient.setQueryData(queryKeys.me.profile(), updated);
          } catch (cause) {
            setError(errorText(cause, '연결을 해제하지 못했어요. 다시 시도해 주세요.'));
          }
        },
      },
    ]);
  return (
    <Card testID="login-accounts">
      <AppText variant="heading">로그인 계정</AppText>
      {profile.providers.map((provider) => (
        <View key={provider} style={styles.row} testID={`account-${provider}`}>
          <AppText style={styles.flex}>{PROVIDER_LABEL[provider] ?? provider}</AppText>
          <Pressable accessibilityRole="button" onPress={() => unlink(provider)} testID={`unlink-${provider}`}>
            <AppText tone="secondary">연결 해제</AppText>
          </Pressable>
        </View>
      ))}
      {/* 다른 제공자 계정 연결은 소셜 로그인과 함께 붙인다(A5). */}
      <Button title="다른 계정 연결 (준비 중)" variant="secondary" disabled onPress={() => undefined} />
      {error ? (
        <AppText tone="danger" accessibilityRole="alert" testID="accounts-error">
          {error}
        </AppText>
      ) : null}
    </Card>
  );
}

function Menu() {
  const version = appVersionText();
  const open = (url: string | null) => {
    if (url) {
      openExternalLink(url).catch(() => undefined);
    }
  };
  return (
    <Card testID="my-menu">
      {PUSH_NOTIFICATIONS_ENABLED ? (
        <MenuRow label="알림 구독 설정" onPress={() => router.push(routes.notificationSettings())} />
      ) : null}
      <MenuRow label="관심 동네 관리" onPress={() => router.push(routes.interestNeighborhoods())} />
      <MenuRow
        label="이용약관"
        detail={TERMS_OF_SERVICE_URL ? undefined : '준비 중'}
        onPress={TERMS_OF_SERVICE_URL ? () => open(TERMS_OF_SERVICE_URL) : undefined}
      />
      <MenuRow
        label="개인정보처리방침"
        detail={PRIVACY_POLICY_URL ? undefined : '준비 중'}
        onPress={PRIVACY_POLICY_URL ? () => open(PRIVACY_POLICY_URL) : undefined}
      />
      <MenuRow label="앱 버전" detail={version ?? '정보 없음'} testID="app-version" />
    </Card>
  );
}

function AccountActions({ signOutDeps }: { signOutDeps?: SignOutDeps }) {
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const logout = () =>
    Alert.alert('로그아웃할까요?', LOGOUT_CONFIRM, [
      { text: '취소', style: 'cancel' },
      {
        text: '로그아웃',
        onPress: async () => {
          setBusy(true);
          try {
            await performLogout(signOutDeps);
          } finally {
            setBusy(false);
          }
        },
      },
    ]);

  const withdraw = () =>
    Alert.alert('탈퇴할까요?', WITHDRAW_EXPLAIN, [
      { text: '취소', style: 'cancel' },
      {
        text: '탈퇴',
        style: 'destructive',
        onPress: async () => {
          setError(null);
          setBusy(true);
          try {
            await performWithdraw(signOutDeps);
            Alert.alert('탈퇴했어요', WITHDRAW_DONE);
          } catch (cause) {
            // 인증 만료로 이미 비회원이 됐다면 가입 시트 대신 사유만 보여준다.
            setError(
              isApiError(cause, ErrorCodes.AUTHENTICATION_FAILED)
                ? errorText(cause, '탈퇴하지 못했어요. 다시 로그인해 주세요.')
                : errorText(cause, '탈퇴하지 못했어요. 다시 시도해 주세요.'),
            );
          } finally {
            setBusy(false);
          }
        },
      },
    ]);

  return (
    <View style={styles.block}>
      <Button title="로그아웃" variant="secondary" onPress={logout} pending={busy} testID="logout" />
      <Pressable accessibilityRole="button" onPress={withdraw} disabled={busy} testID="withdraw">
        <AppText tone="secondary" style={styles.withdraw}>
          탈퇴하기
        </AppText>
      </Pressable>
      {error ? (
        <AppText tone="danger" accessibilityRole="alert" testID="account-error">
          {error}
        </AppText>
      ) : null}
    </View>
  );
}

// ── 공용 ──

function Card({ children, testID }: { children: ReactNode; testID?: string }) {
  return (
    <View style={styles.card} testID={testID}>
      {children}
    </View>
  );
}

function SectionHeader({ title, onMore, moreTestID }: { title: string; onMore: () => void; moreTestID: string }) {
  return (
    <View style={styles.row}>
      <AppText variant="heading" style={styles.flex}>
        {title}
      </AppText>
      <Pressable accessibilityRole="button" onPress={onMore} testID={moreTestID}>
        <AppText tone="secondary">전체 보기</AppText>
      </Pressable>
    </View>
  );
}

function MenuRow({
  label,
  detail,
  onPress,
  testID,
}: {
  label: string;
  detail?: string;
  onPress?: () => void;
  testID?: string;
}) {
  const content = (
    <>
      <AppText style={styles.flex}>{label}</AppText>
      {detail ? (
        <AppText tone="secondary" testID={testID}>
          {detail}
        </AppText>
      ) : (
        <AppText tone="secondary">›</AppText>
      )}
    </>
  );
  if (!onPress) {
    return <View style={[styles.row, styles.menuRow]}>{content}</View>;
  }
  return (
    <Pressable accessibilityRole="button" onPress={onPress} style={[styles.row, styles.menuRow]}>
      {content}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  loading: { marginTop: spacing.xl },
  card: {
    backgroundColor: colors.surface,
    borderRadius: radii.lg,
    padding: spacing.lg,
    gap: spacing.sm,
  },
  block: { gap: spacing.sm },
  row: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  flex: { flex: 1 },
  menuRow: { minHeight: 44 },
  input: {
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: radii.md,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    color: colors.textPrimary,
  },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm },
  chip: {
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    borderRadius: radii.pill,
    borderWidth: 1,
    borderColor: colors.border,
    backgroundColor: colors.surface,
  },
  chipActive: { backgroundColor: colors.primary, borderColor: colors.primary },
  withdraw: { textAlign: 'center', paddingVertical: spacing.sm },
});
