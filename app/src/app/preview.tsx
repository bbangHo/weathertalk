// /preview: 데모 모드 진입 주소. 데모 여부는 앱을 불러올 때 주소로 정해지므로(demo/demoMode) 여기서는 홈으로만 보낸다.
import { Redirect } from 'expo-router';

export default function PreviewRoute() {
  return <Redirect href="/" />;
}
