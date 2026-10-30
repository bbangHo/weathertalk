// SCR-10 가입 (07 SCR-10, §3 흐름·규칙 1~3, §4 규칙 4, §6.2, 01 FR-MEM-02·03·12)
// 제공자 버튼은 providerLogin(스토어 앱: A5에서 붙인다, 지금은 "준비 중" / 앱인토스: 토스 식별키). 개발 빌드에서만 개발 전용 로그인을 보여준다.
// 가입 중 푸시 권한을 묻지 않는다(§3 규칙 3).
import { useQueryClient } from '@tanstack/react-query';
import { router } from 'expo-router';
import { useCallback, useEffect, useReducer, useRef, useState } from 'react';
import { Pressable, StyleSheet, TextInput, View } from 'react-native';

import { api } from '@/api';
import type { ApiClient } from '@/api/client';
import { isApiError } from '@/api/errors';
import { AppText, Button, Screen } from '@/components/ui';
import { DEV_ENTRY_ENABLED } from '@/config/env';
import { pendingActionStore } from '@/pending/pendingAction';
import { openExternalLink } from '@/platform/openExternalLink';
import { restoreAfterSignup } from '@/pending/restore';
import { queryKeys } from '@/query/queryKeys';
import { generalStore, type TemperatureSensitivity } from '@/storage';
import { colors, radii, spacing } from '@/theme';

import {
  completeSignup,
  devLogin,
  getMyProfile,
  getTerms,
  type LoginResponse,
  reagreeTerms,
  SENSITIVITY_LABEL,
  SENSITIVITY_ORDER,
  type TermsResponse,
} from './api';
import { completeLogin, discardedNotice, type PostLoginDeps } from './completeLogin';
import { PROVIDER_BUTTONS, type ProviderButton } from './providerLogin';
import { signupSheet } from './signupSheet';
import { allRequiredAgreed, initialSignupState, signupReducer, termsKey, toAgreements } from './signupMachine';
import { termsStatus } from './termsStatus';

export type SignupScreenProps = {
  mode: 'signup' | 'reagree';
  /** 개발 전용 로그인 노출. 기본은 개발 빌드 여부(__DEV__) */
  devEntryEnabled?: boolean;
  /** 테스트에서 로그인 직후 처리를 바꿀 때 */
  postLoginDeps?: PostLoginDeps;
  /** 로그인 수단 버튼. 기본은 빌드 대상(providerLogin)의 버튼 */
  providerButtons?: ProviderButton[];
};

const SIGNUP_TICKET_INVALID = 'SIGNUP_TICKET_INVALID';

function errorMessage(error: unknown, fallback: string): string {
  return isApiError(error) && error.detail ? error.detail : fallback;
}

function closeSheet() {
  if (router.canGoBack()) {
    router.back();
  } else {
    router.replace('/');
  }
}

export function SignupScreen({
  mode,
  devEntryEnabled = DEV_ENTRY_ENABLED,
  postLoginDeps,
  providerButtons = PROVIDER_BUTTONS,
}: SignupScreenProps) {
  const queryClient = useQueryClient();
  const [state, dispatch] = useReducer(signupReducer, undefined, () =>
    initialSignupState(mode, termsStatus.getState().termsToReagree),
  );
  const [error, setError] = useState<string | null>(null);
  const finishedRef = useRef(false);

  useEffect(() => {
    signupSheet.setMounted(true);
    return () => {
      signupSheet.setMounted(false);
      if (finishedRef.current) {
        return;
      }
      // 닫기 제스처 등으로 끝내지 않고 닫힘: 가입 취소면 보류 행동만 지우고, 재동의 "나중에"면 입력을 복원한다.
      if (mode === 'signup') {
        pendingActionStore.clear().catch(() => undefined);
      } else {
        restoreAfterSignup(() => undefined).catch(() => undefined);
      }
    };
  }, [mode]);

  useEffect(() => {
    if (state.step === 'finished' && !finishedRef.current) {
      finishedRef.current = true;
      restoreAfterSignup(closeSheet).catch(() => undefined);
    }
  }, [state]);

  const handleLogin = useCallback(
    async (response: LoginResponse) => {
      if (response.registered && response.tokens) {
        const result = await completeLogin(response.tokens, response.member, postLoginDeps);
        const member = response.member ?? {
          memberId: '',
          nickname: '',
          temperatureSensitivity: 'NORMAL' as const,
          termsReagreementRequired: false,
          termsToReagree: [],
        };
        dispatch({ type: 'LOGGED_IN', member, discarded: result.discarded });
        return;
      }
      if (response.signupTicket) {
        const terms = response.terms ?? (await getTerms(api));
        dispatch({ type: 'SIGNUP_NEEDED', ticket: response.signupTicket.ticket, terms });
        return;
      }
      throw new Error('알 수 없는 로그인 응답');
    },
    [postLoginDeps],
  );

  const cancel = async () => {
    finishedRef.current = true;
    await pendingActionStore.clear().catch(() => undefined);
    closeSheet();
  };

  return (
    <Screen>
      {state.step === 'provider' ? (
        <ProviderStep
          error={state.error ?? error}
          devEntryEnabled={devEntryEnabled}
          providerButtons={providerButtons}
          onProviderLogin={async (login) => {
            setError(null);
            try {
              await handleLogin(await login(api));
            } catch (cause) {
              setError(errorMessage(cause, '로그인하지 못했어요. 다시 시도해 주세요.'));
            }
          }}
          onDevLogin={async (id) => {
            setError(null);
            try {
              await handleLogin(await devLogin(api, id));
            } catch (cause) {
              setError(errorMessage(cause, '로그인하지 못했어요. 다시 시도해 주세요.'));
            }
          }}
          onCancel={cancel}
        />
      ) : null}

      {state.step === 'terms' ? (
        <TermsStep
          title="약관에 동의해 주세요"
          terms={state.terms}
          submitTitle="다음"
          onSubmit={async (agreed) => {
            dispatch({ type: 'TERMS_CONFIRMED', agreements: toAgreements(state.terms, agreed) });
          }}
          secondary={{ title: '취소', onPress: cancel }}
        />
      ) : null}

      {state.step === 'sensitivity' ? (
        <SensitivityStep
          error={error}
          onBack={() => {
            setError(null);
            dispatch({ type: 'BACK' });
          }}
          onSubmit={async (sensitivity) => {
            setError(null);
            try {
              const response = await completeSignup(api, {
                signupTicket: state.ticket,
                agreements: state.agreements,
                temperatureSensitivity: sensitivity,
              });
              if (!response.tokens) {
                throw new Error('토큰 없음');
              }
              const result = await completeLogin(response.tokens, response.member, postLoginDeps);
              dispatch({ type: 'SIGNED_UP', nickname: response.member?.nickname ?? '', discarded: result.discarded });
            } catch (cause) {
              if (isApiError(cause) && cause.code === SIGNUP_TICKET_INVALID) {
                dispatch({
                  type: 'TICKET_INVALID',
                  message: errorMessage(cause, '가입 시간이 지났어요. 다시 로그인해 주세요.'),
                });
                return;
              }
              setError(errorMessage(cause, '가입하지 못했어요. 다시 시도해 주세요.'));
            }
          }}
        />
      ) : null}

      {state.step === 'reagree' ? (
        <ReagreeStep
          terms={state.terms}
          onAgree={async (agreed) => {
            const profile = await reagreeTerms(api, toAgreements(state.terms, agreed));
            termsStatus.update(profile.termsReagreementRequired, profile.termsToReagree);
            queryClient.setQueryData(queryKeys.me.profile(), profile);
            dispatch({ type: 'REAGREED' });
          }}
          onLater={() => dispatch({ type: 'LATER' })}
        />
      ) : null}

      {state.step === 'done' ? (
        <View style={styles.block} testID="signup-done">
          <AppText variant="title">가입했어요</AppText>
          <AppText>{`닉네임은 '${state.nickname}'이에요. 마이페이지에서 바꿀 수 있어요.`}</AppText>
          {discardedNotice(state.discarded) ? <AppText tone="secondary">{discardedNotice(state.discarded)}</AppText> : null}
          <Button title="확인" onPress={() => dispatch({ type: 'ACK' })} testID="signup-ack" />
        </View>
      ) : null}

      {state.step === 'notice' ? (
        <View style={styles.block} testID="signup-notice">
          <AppText variant="heading">로그인했어요</AppText>
          <AppText tone="secondary">{discardedNotice(state.discarded)}</AppText>
          <Button title="확인" onPress={() => dispatch({ type: 'ACK' })} testID="signup-ack" />
        </View>
      ) : null}
    </Screen>
  );
}

function ProviderStep({
  error,
  devEntryEnabled,
  providerButtons,
  onProviderLogin,
  onDevLogin,
  onCancel,
}: {
  error: string | null;
  devEntryEnabled: boolean;
  providerButtons: ProviderButton[];
  onProviderLogin: (login: (client: ApiClient) => Promise<LoginResponse>) => Promise<void>;
  onDevLogin: (id: string) => Promise<void>;
  onCancel: () => Promise<void>;
}) {
  const [devId, setDevId] = useState('');
  return (
    <View style={styles.block}>
      <AppText variant="title">가입하고 남겨 보세요</AppText>
      <AppText tone="secondary">로그인하면 체감 기록·피드백·글을 남길 수 있어요. 닉네임은 자동으로 정해져요.</AppText>
      {providerButtons.map(({ key, title, login }) =>
        login ? (
          <Button key={key} title={title} testID={`provider-${key}`} onPress={() => onProviderLogin(login)} />
        ) : (
          <Button key={key} title={title} variant="secondary" disabled onPress={() => undefined} />
        ),
      )}
      {error ? (
        <AppText tone="danger" accessibilityRole="alert" testID="signup-error">
          {error}
        </AppText>
      ) : null}
      {devEntryEnabled ? (
        <View style={styles.dev} testID="dev-login">
          <AppText variant="caption" tone="secondary">
            개발 빌드 전용
          </AppText>
          <TextInput
            testID="dev-login-id"
            value={devId}
            onChangeText={setDevId}
            placeholder="개발 로그인 ID (영문·숫자)"
            autoCapitalize="none"
            autoCorrect={false}
            style={styles.input}
          />
          <Button
            title="개발 로그인"
            testID="dev-login-submit"
            disabled={devId.trim() === ''}
            onPress={() => onDevLogin(devId.trim())}
          />
        </View>
      ) : null}
      <Button title="취소" variant="secondary" onPress={onCancel} testID="signup-cancel" />
    </View>
  );
}

function Check({ checked, label, onPress, testID }: { checked: boolean; label: string; onPress: () => void; testID?: string }) {
  return (
    <Pressable
      accessibilityRole="checkbox"
      accessibilityState={{ checked }}
      onPress={onPress}
      style={styles.checkRow}
      testID={testID}
    >
      <AppText tone={checked ? 'primary' : 'secondary'}>{checked ? '☑' : '☐'}</AppText>
      <AppText style={styles.flex}>{label}</AppText>
    </Pressable>
  );
}

function TermsList({
  terms,
  agreed,
  onToggle,
}: {
  terms: TermsResponse[];
  agreed: Set<string>;
  onToggle: (key: string) => void;
}) {
  return (
    <>
      {terms.map((item) => {
        const key = termsKey(item);
        return (
          <View key={key} style={styles.termRow}>
            <Check
              checked={agreed.has(key)}
              label={`${item.required ? '[필수]' : '[선택]'} ${item.title}`}
              onPress={() => onToggle(key)}
              testID={`terms-${item.kind}`}
            />
            {item.contentUrl ? (
              <Pressable accessibilityRole="link" onPress={() => openExternalLink(item.contentUrl!).catch(() => undefined)}>
                <AppText tone="secondary">보기</AppText>
              </Pressable>
            ) : null}
          </View>
        );
      })}
    </>
  );
}

function useAgreedSet() {
  const [agreed, setAgreed] = useState<Set<string>>(new Set());
  const toggle = (key: string) =>
    setAgreed((prev) => {
      const next = new Set(prev);
      if (next.has(key)) {
        next.delete(key);
      } else {
        next.add(key);
      }
      return next;
    });
  return { agreed, setAgreed, toggle };
}

function TermsStep({
  title,
  terms,
  submitTitle,
  onSubmit,
  secondary,
  error,
}: {
  title: string;
  terms: TermsResponse[];
  submitTitle: string;
  onSubmit: (agreed: Set<string>) => Promise<void>;
  secondary: { title: string; onPress: () => void | Promise<void> };
  error?: string | null;
}) {
  const { agreed, setAgreed, toggle } = useAgreedSet();
  const all = terms.length > 0 && terms.every((item) => agreed.has(termsKey(item)));
  return (
    <View style={styles.block} testID="signup-terms">
      <AppText variant="title">{title}</AppText>
      <Check
        checked={all}
        label="전체 동의"
        onPress={() => setAgreed(all ? new Set() : new Set(terms.map(termsKey)))}
        testID="terms-all"
      />
      <TermsList terms={terms} agreed={agreed} onToggle={toggle} />
      {error ? (
        <AppText tone="danger" accessibilityRole="alert">
          {error}
        </AppText>
      ) : null}
      <Button
        title={submitTitle}
        testID="terms-submit"
        disabled={!allRequiredAgreed(terms, agreed)}
        onPress={() => onSubmit(agreed)}
      />
      <Button title={secondary.title} variant="secondary" onPress={secondary.onPress} testID="terms-secondary" />
    </View>
  );
}

function SensitivityStep({
  error,
  onBack,
  onSubmit,
}: {
  error: string | null;
  onBack: () => void;
  onSubmit: (sensitivity: TemperatureSensitivity) => Promise<void>;
}) {
  const [selected, setSelected] = useState<TemperatureSensitivity | null>(null);
  const touched = useRef(false);
  // 홈에서 고른 기기 민감도가 있으면 미리 선택한다(§6.2 신규 가입).
  useEffect(() => {
    generalStore
      .get('guestTemperatureSensitivity')
      .then((value) => {
        if (value && !touched.current) {
          setSelected(value);
        }
      })
      .catch(() => undefined);
  }, []);
  return (
    <View style={styles.block} testID="signup-sensitivity">
      <AppText variant="title">나는 어느 쪽인가요?</AppText>
      <AppText tone="secondary">같은 날씨도 사람마다 다르게 느껴요. 나와 비슷한 사람들의 체감을 보여드려요.</AppText>
      {SENSITIVITY_ORDER.map((value) => (
        <Pressable
          key={value}
          accessibilityRole="radio"
          accessibilityState={{ selected: selected === value }}
          onPress={() => {
            touched.current = true;
            setSelected(value);
          }}
          style={[styles.choice, selected === value && styles.choiceSelected]}
          testID={`sensitivity-${value}`}
        >
          <AppText>{SENSITIVITY_LABEL[value]}</AppText>
        </Pressable>
      ))}
      {error ? (
        <AppText tone="danger" accessibilityRole="alert" testID="signup-error">
          {error}
        </AppText>
      ) : null}
      <Button
        title="가입하기"
        testID="signup-submit"
        disabled={selected === null}
        onPress={() => (selected ? onSubmit(selected) : undefined)}
      />
      <Button title="이전" variant="secondary" onPress={onBack} />
    </View>
  );
}

function ReagreeStep({
  terms: initialTerms,
  onAgree,
  onLater,
}: {
  terms: TermsResponse[];
  onAgree: (agreed: Set<string>) => Promise<void>;
  onLater: () => void;
}) {
  const [terms, setTerms] = useState(initialTerms);
  const [error, setError] = useState<string | null>(null);
  // 쓰기 오류로만 재동의를 알게 됐으면 바뀐 약관 목록을 회원 프로필에서 읽는다.
  useEffect(() => {
    if (initialTerms.length > 0) {
      return;
    }
    getMyProfile(api)
      .then((profile) => {
        termsStatus.update(profile.termsReagreementRequired, profile.termsToReagree);
        setTerms(profile.termsToReagree);
      })
      .catch((cause) => setError(errorMessage(cause, '바뀐 약관을 불러오지 못했어요.')));
  }, [initialTerms]);
  return (
    <TermsStep
      title="약관이 바뀌었어요"
      terms={terms}
      submitTitle="동의하기"
      error={error}
      onSubmit={async (agreed) => {
        setError(null);
        try {
          await onAgree(agreed);
        } catch (cause) {
          setError(errorMessage(cause, '동의를 저장하지 못했어요. 다시 시도해 주세요.'));
        }
      }}
      secondary={{ title: '나중에', onPress: onLater }}
    />
  );
}

const styles = StyleSheet.create({
  block: { gap: spacing.md },
  dev: {
    gap: spacing.sm,
    padding: spacing.md,
    borderRadius: radii.md,
    borderWidth: 1,
    borderStyle: 'dashed',
    borderColor: colors.border,
  },
  input: {
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: radii.md,
    padding: spacing.md,
    color: colors.textPrimary,
  },
  checkRow: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm, flex: 1, paddingVertical: spacing.xs },
  termRow: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  flex: { flex: 1 },
  choice: {
    padding: spacing.md,
    borderRadius: radii.md,
    borderWidth: 1,
    borderColor: colors.border,
  },
  choiceSelected: { borderColor: colors.primary, borderWidth: 2 },
});
