import { useLocalSearchParams } from 'expo-router';

import { SignupScreen } from '@/auth/SignupScreen';

export default function SignupRoute() {
  const { mode } = useLocalSearchParams<{ mode?: string }>();
  return <SignupScreen mode={mode === 'reagree' ? 'reagree' : 'signup'} />;
}
