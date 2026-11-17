// 날씨톡톡 백엔드 부하테스트 (k6). 실행법·시나리오·합격 기준은 README.md.
//   SCENARIO=smoke|load|stress|spike (기본 smoke)
//   BASE_URL      대상 서버 (기본 http://localhost:18080)
//   PEAK_TPS      load의 목표 TPS (기본 20). spike·stress는 이 값의 배수로 움직인다
//   HOT_SHARE     씨앗 동네 5곳에 더 얹는 요청 비율 (기본 0.05). 나머지는 전국 3,564개 동네에 인구 비례로 퍼진다
//   USERS         setup에서 만들 테스트 회원 수 (기본: smoke 3, 그 밖 50)
//   ALLOW_REMOTE  로컬이 아닌 서버에 쏘려면 1. 미리보기·운영 서버는 사용자 확인 없이 쓰지 않는다
import http from 'k6/http';
import { check, fail } from 'k6';

const SCENARIO = __ENV.SCENARIO || 'smoke';
const BASE_URL = (__ENV.BASE_URL || 'http://localhost:18080').replace(/\/$/, '');
const PEAK = Number(__ENV.PEAK_TPS || 20); // TPS = 초당 API 호출 처리 건수(반복 1번 = 요청 1건이라 RPS와 같다)
const HOT_SHARE = Number(__ENV.HOT_SHARE || 0.05);

// 시도별 주민등록 인구(만 명, 2025년 초 근사). 수도권 합 2,605만(50.8%)·경기 1,370만은 행정안전부 2025-01 통계 보도와 맞췄고,
// 나머지는 근삿값이다. 요청 분포의 모양을 정하는 데만 쓰므로 몇 % 차이는 결과에 영향이 없다.
const SIDO_POPULATION = {
  서울: 933, 부산: 326, 대구: 236, 인천: 302, 광주: 140, 대전: 144, 울산: 110, 세종: 39, 경기: 1370,
  강원: 151, 충북: 159, 충남: 213, 전북: 173, 전남: 179, 경북: 253, 경남: 323, 제주: 67,
  전남광주통합: 140 + 179, // 동네 기준 데이터(2026-07)는 광주·전남을 '전남광주통합특별시' 하나로 둔다
};
// 시도 안에서는 읍·면·동 한 곳당 평균 인구 비율로 나눈다(동·읍은 대개 1~3만 명, 면은 수천 명 — 근사).
const UNIT_WEIGHT = { 동: 1, 읍: 1, 면: 0.2 };
const SIDO_ALIAS = { 충청북: '충북', 충청남: '충남', 전라북: '전북', 전라남: '전남', 경상북: '경북', 경상남: '경남' };
function sidoKey(name) {
  const k = name.replace(/(특별자치도|특별자치시|특별시|광역시|도)$/, '');
  return SIDO_ALIAS[k] || k;
}
function cumulative(weights) {
  let s = 0;
  return weights.map((w) => (s += w));
}
// 누적 가중치에서 이진 탐색으로 하나 고른다.
function pickIndex(cum) {
  const r = Math.random() * cum[cum.length - 1];
  let lo = 0;
  let hi = cum.length - 1;
  while (lo < hi) {
    const mid = (lo + hi) >> 1;
    if (cum[mid] > r) hi = mid; else lo = mid + 1;
  }
  return lo;
}
const USERS = Math.max(2, Number(__ENV.USERS || (SCENARIO === 'smoke' ? 3 : 50))); // 좋아요는 남의 글이어야 해서 2명 이상

// 실수로 공개 데모(미리보기)나 운영에 부하를 주지 않게 막는다. backend = compose.minipc.yml 안의 부하테스트 백엔드.
if (!/^https?:\/\/(localhost|127\.0\.0\.1|host\.docker\.internal|backend)(:\d+)?$/.test(BASE_URL) && __ENV.ALLOW_REMOTE !== '1') {
  throw new Error(`로컬이 아닌 대상(${BASE_URL})입니다. 사용자 확인을 받았으면 ALLOW_REMOTE=1 을 붙이세요.`);
}

// 씨앗 동네 좌표. 인구 비례 몫에 더해 요청의 HOT_SHARE가 여기에 몰린다(초기 커뮤니티를 채운 동네). 상세·좋아요 글도 여기에 둔다.
const SPOTS = [
  { lat: 37.5006, lon: 127.0364 }, // 서울 강남구 역삼
  { lat: 37.5045, lon: 127.0050 }, // 서울 서초구 반포
  { lat: 37.5559, lon: 126.9236 }, // 서울 마포구 서교
  { lat: 37.5133, lon: 127.1001 }, // 서울 송파구 잠실
  { lat: 37.2636, lon: 127.0286 }, // 경기 수원시
];
const SEARCH_WORDS = ['역삼', '반포', '서교', '잠실', '수원', '해운대', '연남', '판교', '중앙동', '신림'];
const TEMPS = ['COLD', 'SLIGHTLY_COLD', 'JUST_RIGHT', 'SLIGHTLY_HOT'];
const SENSITIVITIES = ['HEAT_SENSITIVE', 'NORMAL', 'COLD_SENSITIVE'];
const REPORT_KINDS = ['RAIN', 'OVERCAST', 'CLEAR'];

const SCENARIOS = {
  // 모든 요청 종류를 순서대로 두 번씩. 스크립트·서버가 동작하는지만 본다.
  smoke: { executor: 'shared-iterations', vus: 1, iterations: 20, maxDuration: '2m' },
  // 출시 6개월 목표의 10배(MAU 3만) 아침 피크 가정. README '트래픽 가정'
  load: {
    executor: 'ramping-arrival-rate', startRate: 0, timeUnit: '1s', preAllocatedVUs: 50, maxVUs: 300,
    stages: [{ target: PEAK, duration: '2m' }, { target: PEAK, duration: '10m' }, { target: 0, duration: '1m' }],
  },
  // 어디서 무너지는지 찾는다. 오류율 5%를 넘으면 스스로 멈춘다(thresholds의 abortOnFail).
  stress: {
    executor: 'ramping-arrival-rate', startRate: 0, timeUnit: '1s', preAllocatedVUs: 100, maxVUs: 1500,
    stages: [1, 2.5, 5, 7.5, 10].flatMap((m) => [
      { target: PEAK * m, duration: '1m' }, { target: PEAK * m, duration: '3m' },
    ]).concat([{ target: 0, duration: '1m' }]),
  },
  // 아침 7시 날씨 알림 직후 한꺼번에 여는 상황.
  spike: {
    executor: 'ramping-arrival-rate', startRate: Math.ceil(PEAK / 4), timeUnit: '1s', preAllocatedVUs: 100, maxVUs: 800,
    stages: [
      { target: Math.ceil(PEAK / 4), duration: '1m' },
      { target: PEAK * 3, duration: '10s' }, { target: PEAK * 3, duration: '2m' },
      { target: Math.ceil(PEAK / 4), duration: '10s' }, { target: Math.ceil(PEAK / 4), duration: '2m' },
    ],
  },
};
if (!SCENARIOS[SCENARIO]) throw new Error(`알 수 없는 SCENARIO: ${SCENARIO}`);

export const options = {
  setupTimeout: '5m',
  scenarios: { [SCENARIO]: SCENARIOS[SCENARIO] },
  // 합격 기준(README '합격 기준'). kind: home(홈 조합) · read(그 밖 조회) · write(쓰기). name: 요청 종류.
  thresholds: {
    http_req_failed: [{ threshold: 'rate<0.01', abortOnFail: SCENARIO === 'stress', delayAbortEval: '30s' }],
    checks: ['rate>0.99'],
    'http_req_duration{kind:read}': ['p(95)<300', 'p(99)<1000'],
    'http_req_duration{kind:write}': ['p(95)<500', 'p(99)<1500'],
    // 사업계획 품질 KPI "2초 넘는 지연 0건"
    'http_req_duration{kind:home}': ['p(95)<800', 'p(99)<2000', 'max<5000'],
  },
};

const json = (token, extra) => ({
  headers: Object.assign({ 'Content-Type': 'application/json' }, token ? { Authorization: `Bearer ${token}` } : {}, extra),
});
const pick = (a) => a[Math.floor(Math.random() * a.length)];
const idemKey = () => `lt-${Date.now()}-${Math.random().toString(36).slice(2, 12)}`;

// 개발 전용 로그인(local·staging 프로파일에만 있다)으로 테스트 회원을 만들고 토큰을 받는다.
// 회원 ID는 고정(loadtest-N)이라 다시 돌리면 같은 회원으로 로그인만 한다.
function loginUser(n, terms) {
  const login = http.post(`${BASE_URL}/api/v1/dev/login`,
    JSON.stringify({ providerUserId: `loadtest-${n}`, email: `loadtest-${n}@example.invalid` }), json());
  if (login.status !== 200) fail(`개발 로그인 실패(${login.status}): local·staging 프로파일인지 확인하세요. ${login.body}`);
  let body = login.json();
  if (!body.registered) {
    const signup = http.post(`${BASE_URL}/api/v1/signup`, JSON.stringify({
      signupTicket: body.signupTicket.ticket,
      agreements: terms.map((t) => ({ kind: t.kind, version: t.version, agreed: true })),
      temperatureSensitivity: pick(SENSITIVITIES),
    }), json());
    if (signup.status !== 200) fail(`가입 실패(${signup.status}): ${signup.body}`);
    body = signup.json();
  }
  return body.tokens.accessToken;
}

export function setup() {
  const terms = http.get(`${BASE_URL}/api/v1/terms`).json();
  const tokens = [];
  for (let i = 1; i <= USERS; i++) tokens.push(loginUser(i, terms));

  const neighborhoods = SPOTS.map((s) => {
    const r = http.post(`${BASE_URL}/api/v1/neighborhoods/locate`, JSON.stringify({ latitude: s.lat, longitude: s.lon, accuracyMeters: 30 }), json());
    if (r.status !== 200) fail(`동네 판정 실패(${r.status}): 동네 기준 데이터가 적재됐는지 확인하세요. ${r.body}`);
    return Object.assign({ id: r.json('id') }, s);
  });

  // 전국 동네 목록: 시도 → 시군구 → 동네 단계 조회(약 270건). 대표점 좌표로 쓰기 위치를 만든다.
  const all = [];
  for (const sido of http.get(`${BASE_URL}/api/v1/neighborhoods/regions`).json('names')) {
    const sidoStart = all.length;
    const sigungus = http.get(`${BASE_URL}/api/v1/neighborhoods/regions?sido=${encodeURIComponent(sido)}`).json('names');
    const pages = http.batch(sigungus.map((sg) => ['GET',
      `${BASE_URL}/api/v1/neighborhoods/regions?sido=${encodeURIComponent(sido)}&sigungu=${encodeURIComponent(sg)}`]));
    for (const page of pages) {
      for (const n of page.json('neighborhoods')) {
        if (n.representativePoint) {
          all.push({ id: n.id, lat: n.representativePoint.latitude, lon: n.representativePoint.longitude,
            sido: sidoKey(sido), unit: UNIT_WEIGHT[n.eupmyeondongName.slice(-1)] || 1 });
        }
      }
    }
    // 시도 인구를 그 시도 동네들에 읍·면·동 가중치대로 나눈다.
    const people = SIDO_POPULATION[sidoKey(sido)];
    if (!people) fail(`인구 표에 없는 시도 이름: ${sido} (SIDO_POPULATION에 추가하세요)`);
    const units = all.slice(sidoStart).reduce((s, n) => s + n.unit, 0);
    for (const n of all.slice(sidoStart)) n.weight = (people * n.unit) / units;
  }
  if (all.length < 1000) fail(`전국 동네 목록이 너무 적습니다(${all.length}).`);
  const cum = cumulative(all.map((n) => n.weight));
  // 자체 확인: 수도권(서울·인천·경기) 몫이 인구 비율(약 51%)과 맞는지.
  const share = all.filter((n) => ['서울', '인천', '경기'].includes(n.sido)).reduce((s, n) => s + n.weight, 0) / cum[cum.length - 1];
  if (share < 0.45 || share > 0.56) fail(`수도권 요청 몫이 ${(share * 100).toFixed(1)}%로 인구 비율(약 51%)과 다릅니다.`);
  console.log(`전국 동네 ${all.length}곳, 수도권 몫 ${(share * 100).toFixed(1)}%`);

  // 상세·좋아요 대상 글을 동네마다 하나씩 만들어 둔다. 내 글에는 좋아요할 수 없어(SELF_LIKE_NOT_ALLOWED) 작성자를 기억한다.
  const posts = neighborhoods.map((nb, i) => {
    const r = http.post(`${BASE_URL}/api/v1/community/posts`,
      JSON.stringify({ body: `부하테스트 준비 글 ${i}`, latitude: nb.lat, longitude: nb.lon, accuracyMeters: 30 }),
      json(tokens[i % tokens.length], { 'Idempotency-Key': idemKey() }));
    if (r.status !== 200) fail(`준비 글 작성 실패(${r.status}): ${r.body}`);
    return { id: r.json('postId'), author: i % tokens.length };
  });
  return { tokens, neighborhoods, all: all.map(({ id, lat, lon }) => ({ id, lat, lon })), cum, posts };
}

// 요청 하나 = 반복 하나. 가중치 합 100 = 읽기 88 : 쓰기 12 (README '읽기·쓰기 비율').
const ACTIONS = [
  [35, 'home'], [20, 'feed'], [10, 'feeling'], [8, 'postDetail'], [8, 'reports'], [7, 'search'],
  [6, 'recordFeeling'], [3, 'like'], [1.5, 'createPost'], [1.5, 'fieldReport'],
];
const TOTAL = ACTIONS.reduce((s, [w]) => s + w, 0);
function chooseAction() {
  let r = Math.random() * TOTAL;
  for (const [w, name] of ACTIONS) if ((r -= w) < 0) return name;
  return ACTIONS[0][1];
}

const ok = (res, name) => check(res, { [`${name} 2xx`]: (r) => r.status >= 200 && r.status < 300 });

export default function (data) {
  const nb = Math.random() < HOT_SHARE ? pick(data.neighborhoods) : data.all[pickIndex(data.cum)];
  const token = pick(data.tokens);
  const member = Math.random() < 0.6; // 읽기 요청의 60%는 회원, 나머지는 비회원
  const read = (name) => ({ tags: { name, kind: 'read' } });
  const write = (name) => ({ tags: { name, kind: 'write' } });
  const loc = { latitude: nb.lat, longitude: nb.lon, accuracyMeters: 30 };
  let res;

  const action = SCENARIO === 'smoke' ? ACTIONS[__ITER % ACTIONS.length][1] : chooseAction();
  switch (action) {
    case 'home': {
      const q = member ? '' : `&temperatureSensitivity=${pick(SENSITIVITIES)}`;
      res = http.get(`${BASE_URL}/api/v1/home?neighborhoodId=${nb.id}${q}`, Object.assign(json(member && token), { tags: { name: 'home', kind: 'home' } }));
      ok(res, 'home');
      // 섹션 시간 초과·오류는 200 안에 status FAILED로 숨는다(07 §1.4). 따로 센다.
      check(res, { 'home 섹션 실패 없음': (r) => !String(r.body).includes('"FAILED"') });
      // 모의 날씨 서버(C 모드)가 붙어 있으면 현재 날씨가 채워진다. EMPTY면 원천 연결을 확인한다.
      check(res, { 'home 현재 날씨 있음': (r) => r.status === 200 && r.json('currentWeather.status') === 'OK' });
      break;
    }
    case 'feed':
      res = http.get(`${BASE_URL}/api/v1/community/neighborhoods/${nb.id}/posts?size=20`, Object.assign(json(member && token), read('feed')));
      ok(res, 'feed');
      break;
    case 'feeling':
      res = http.get(`${BASE_URL}/api/v1/feeling/neighborhoods/${nb.id}`, Object.assign(json(member && token), read('feeling')));
      ok(res, 'feeling');
      break;
    case 'postDetail':
      res = http.get(`${BASE_URL}/api/v1/community/posts/${pick(data.posts).id}`, Object.assign(json(member && token), read('postDetail')));
      ok(res, 'postDetail');
      break;
    case 'reports':
      res = http.get(`${BASE_URL}/api/v1/weather/neighborhoods/${nb.id}/field-reports`, Object.assign(json(member && token), read('reports')));
      ok(res, 'reports');
      break;
    case 'search':
      res = http.get(`${BASE_URL}/api/v1/neighborhoods/search?q=${encodeURIComponent(pick(SEARCH_WORDS))}`, read('search'));
      ok(res, 'search');
      break;
    case 'recordFeeling':
      res = http.post(`${BASE_URL}/api/v1/feeling/records`, JSON.stringify(Object.assign({ temperature: pick(TEMPS) }, loc)),
        Object.assign(json(token, { 'Idempotency-Key': idemKey() }), write('recordFeeling')));
      ok(res, 'recordFeeling');
      break;
    case 'like': {
      const post = pick(data.posts);
      const liker = data.tokens[(post.author + 1 + Math.floor(Math.random() * (data.tokens.length - 1))) % data.tokens.length];
      res = http.put(`${BASE_URL}/api/v1/community/posts/${post.id}/like`, JSON.stringify({ liked: Math.random() < 0.5 }),
        Object.assign(json(liker), write('like')));
      ok(res, 'like');
      break;
    }
    case 'createPost':
      res = http.post(`${BASE_URL}/api/v1/community/posts`, JSON.stringify(Object.assign({ body: `부하테스트 글 ${__VU}-${__ITER}` }, loc)),
        Object.assign(json(token, { 'Idempotency-Key': idemKey() }), write('createPost')));
      ok(res, 'createPost');
      break;
    case 'fieldReport':
      res = http.post(`${BASE_URL}/api/v1/weather/field-reports`, JSON.stringify(Object.assign({ kind: pick(REPORT_KINDS) }, loc)),
        Object.assign(json(token, { 'Idempotency-Key': idemKey() }), write('fieldReport')));
      ok(res, 'fieldReport');
      break;
  }
}
