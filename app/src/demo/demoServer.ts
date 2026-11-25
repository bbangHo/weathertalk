// 데모 모드(/preview)용 가짜 서버. API 클라이언트의 fetch 자리에 끼워, 진짜 서버로는 아무 요청도 보내지 않는다.
// 데이터는 접속 시점 기준으로 만들고(“3분 전”이 늘 최신처럼 보이게), 쓰기는 이 메모리 안에서만 반영한다.
import type { MyProfile } from '@/auth/api';
import type { AttachedFeelingRecord, CommentView, PostAuthor, PostSummary } from '@/community/types';
import type {
  FeelingRecordResult,
  FeelingRecordSource,
  HumidityTagName,
  SensitivityFeeling,
  TemperatureTagName,
  WindTagName,
} from '@/feeling/types';
import type { FieldReport, FieldReportAxis, FieldReportKind, FieldReportStatus } from '@/fieldReport/types';
import type { HomeSection, HomeView, HourlyForecastSlot, Outfit, SkyCondition } from '@/home/types';
import type { MyFeelingRecordItem, MyFeelingRecords } from '@/my/api';
import type { NeighborhoodResponse } from '@/neighborhood/types';
import type { TemperatureSensitivity } from '@/storage';

import { type DemoScene, demoImage } from './demoImages';

const MINUTE = 60_000;
const KST_OFFSET = 9 * 60 * MINUTE;

const PLACES: [string, string][] = [
  ['1168064000', '역삼1동'],
  ['1168065000', '역삼2동'],
  ['1168058000', '삼성1동'],
  ['1168060000', '대치2동'],
  ['1168052100', '논현1동'],
  ['1168051000', '신사동'],
  ['1168054500', '청담동'],
  ['1168061000', '도곡1동'],
  ['1168067500', '개포2동'],
  ['1168056500', '압구정동'],
];

export const DEMO_CURRENT_ID = PLACES[0][0];
const DEMO_INTEREST_IDS = [PLACES[2][0], PLACES[3][0]];

const TEMPERATURE_TAGS: TemperatureTagName[] = [
  'VERY_COLD',
  'COLD',
  'SLIGHTLY_COLD',
  'JUST_RIGHT',
  'SLIGHTLY_HOT',
  'HOT',
  'VERY_HOT',
];
const TAG_LABEL: Record<TemperatureTagName, string> = {
  VERY_COLD: '매우 추움',
  COLD: '추움',
  SLIGHTLY_COLD: '조금 추움',
  JUST_RIGHT: '딱 좋음',
  SLIGHTLY_HOT: '조금 더움',
  HOT: '더움',
  VERY_HOT: '매우 더움',
};
const TAG_DEFINITIONS = {
  temperature: TEMPERATURE_TAGS.map((name, i) => ({ name, level: i + 1, label: TAG_LABEL[name] })),
  humidity: (['VERY_DRY', 'DRY', 'COMFORTABLE', 'HUMID', 'VERY_HUMID'] as const).map((name, i) => ({
    name,
    level: i + 1,
    label: ['매우 건조해요', '건조해요', '쾌적해요', '습해요', '매우 습해요'][i],
  })),
  wind: (['CALM', 'LIGHT', 'STRONG'] as const).map((name, i) => ({
    name,
    level: i + 1,
    label: ['잔잔해요', '조금 불어요', '세게 불어요'][i],
  })),
};

// 서울 월평균 최고·최저 기온(대략). 데모 날씨가 계절에 맞아 보이게 한다.
const MONTH_HIGH = [2, 5, 11, 18, 24, 28, 30, 31, 27, 20, 12, 4];
const MONTH_LOW = [-6, -4, 1, 7, 13, 18, 22, 22, 17, 10, 3, -4];

const OUTFITS: Outfit[] = [
  {
    outer: ['롱패딩'],
    top: ['기모 맨투맨', '니트'],
    bottom: ['기모 바지'],
    accessories: ['장갑', '목도리'],
    guide: '최대한 따뜻하게 껴입으세요',
  },
  {
    outer: ['패딩', '두꺼운 코트'],
    top: ['니트', '히트텍'],
    bottom: ['기모 바지', '청바지'],
    accessories: ['목도리'],
    guide: '바람을 막는 겉옷이 좋아요',
  },
  {
    outer: ['코트', '트렌치코트'],
    top: ['니트', '맨투맨'],
    bottom: ['청바지', '면바지'],
    accessories: [],
    guide: '아침저녁엔 한 겹 더 챙기세요',
  },
  {
    outer: ['가디건', '얇은 재킷'],
    top: ['긴팔 티셔츠', '셔츠'],
    bottom: ['면바지', '슬랙스'],
    accessories: [],
    guide: null,
  },
  {
    outer: [],
    top: ['반팔 티셔츠', '얇은 셔츠'],
    bottom: ['면바지'],
    accessories: [],
    guide: '햇볕 아래선 덥게 느껴져요',
  },
  {
    outer: [],
    top: ['반팔', '린넨 셔츠'],
    bottom: ['반바지'],
    accessories: ['모자'],
    guide: '통풍 잘 되는 옷이 좋아요',
  },
  {
    outer: [],
    top: ['민소매', '반팔'],
    bottom: ['반바지'],
    accessories: ['모자', '선크림'],
    guide: '한낮 외출은 피하세요',
  },
];

const GROUP_OFFSET: Record<TemperatureSensitivity, number> = { HEAT_SENSITIVE: 1, NORMAL: 0, COLD_SENSITIVE: -1 };

const NICKNAMES = [
  '테헤란로출근러',
  '역삼고양이',
  '바람막이장인',
  '선릉산책러',
  '추위타는곰',
  '더위엔약해',
  '하늘사진가',
  '강남토박이',
  '아침형인간',
  '점심은국밥',
  '퇴근요정',
  '우산챙겨',
  '카페인중독',
  '새벽러너',
];
const SENSITIVITIES: TemperatureSensitivity[] = ['NORMAL', 'COLD_SENSITIVE', 'HEAT_SENSITIVE'];

const ME_SEED: PostAuthor = { nickname: '날씨요정', temperatureSensitivity: 'NORMAL' };

type SeedComment = [nickIndex: number, minutesAgo: number, body: string, replies?: [number, number, string][]];
type SeedPost = {
  minutesAgo: number;
  author: number | 'me';
  body: string;
  likes: number;
  scene?: DemoScene;
  feeling?: TemperatureTagName;
  comments?: SeedComment[];
};

/** 현재 동네(역삼1동) 글. 시각은 접속 시점 기준 몇 분 전 */
const HOME_POSTS: SeedPost[] = [
  {
    minutesAgo: 3,
    author: 0,
    body: '역삼역 3번 출구 바람 엄청 불어요. 생각보다 쌀쌀하니 겉옷 꼭 챙기세요 🧥',
    likes: 12,
    feeling: 'COLD',
    comments: [
      [2, 2, '맞아요 빌딩 사이 바람 장난 아니에요', [[0, 1, '목도리까지 하고 나올 걸 그랬어요 ㅠ']]],
      [7, 1, '감사합니다! 패딩 입고 나갈게요'],
    ],
  },
  {
    minutesAgo: 8,
    author: 1,
    body: '점심에 테헤란로 걸었는데 햇살은 따뜻한데 그늘은 춥네요. 가디건 하나 걸치면 딱 좋아요',
    likes: 8,
    scene: 'sky',
    comments: [[9, 5, '오늘 하늘 진짜 맑네요 ☀️']],
  },
  {
    minutesAgo: 15,
    author: 2,
    body: '앱에선 미세먼지 보통이라는데 체감상 좀 뿌옇지 않나요? 마스크 챙겼어요 😷',
    likes: 5,
    comments: [
      [4, 12, '저도 목이 칼칼해요', [[2, 10, '현장 제보 남겨 두면 이웃들이 보기 좋겠어요']]],
      [11, 9, '창문 닫아 두는 게 좋겠네요'],
    ],
  },
  {
    minutesAgo: 27,
    author: 6,
    body: '퇴근길 하늘 너무 예뻐서 찍어 봤어요 🌇 오늘 노을 맛집은 역삼역 사거리!',
    likes: 34,
    scene: 'sunset',
    comments: [
      [10, 20, '와 색감 미쳤다…'],
      [3, 18, '내일도 이렇게 맑았으면 좋겠어요', [[6, 15, '내일은 구름 조금이래요!']]],
      [8, 10, '사진 보고 창밖 봤는데 벌써 졌네요 ㅋㅋ'],
    ],
  },
  {
    minutesAgo: 41,
    author: 4,
    body: '추위 많이 타는 편인데 오늘은 패딩까지는 아니고 코트면 충분했어요',
    likes: 9,
    feeling: 'SLIGHTLY_COLD',
  },
  {
    minutesAgo: 58,
    author: 3,
    body: '역삼 근린공원 산책하기 딱 좋은 날씨예요. 강아지도 신났어요 🐶',
    likes: 21,
    scene: 'park',
    comments: [[1, 50, '산책 코스 추천해 주세요!', [[3, 45, '공원 한 바퀴 돌고 선릉 쪽으로 가면 좋아요']]]],
  },
  {
    minutesAgo: 75,
    author: 11,
    body: '혹시 지금 비 오나요? 창밖이 흐려 보여서 우산 챙길지 고민돼요',
    likes: 3,
    comments: [
      [0, 70, '역삼역 쪽은 안 와요!'],
      [5, 68, '구름만 많아요. 우산은 안 챙겨도 될 듯'],
    ],
  },
  {
    minutesAgo: 110,
    author: 5,
    body: '더위 많이 타는 사람 기준으로 오늘은 긴팔 한 장이면 충분해요. 겉옷은 들고만 다녀도 OK',
    likes: 6,
    feeling: 'JUST_RIGHT',
  },
  {
    minutesAgo: 160,
    author: 7,
    body: '선릉 쪽은 바람이 덜한데 역삼역 사거리만 오면 칼바람이에요 🌬️',
    likes: 14,
    comments: [[13, 150, '빌딩풍 때문인가 봐요']],
  },
  {
    minutesAgo: 220,
    author: 'me',
    body: '아침에 얇게 입고 나왔다가 후회 중… 내일은 한 겹 더 입어야겠어요',
    likes: 7,
    feeling: 'SLIGHTLY_COLD',
    comments: [[4, 200, '저도요 ㅠㅠ 오후엔 좀 풀린대요']],
  },
  {
    minutesAgo: 300,
    author: 8,
    body: '오늘 일교차 크대요. 저녁 약속 있으신 분들 따뜻하게 입고 나가세요!',
    likes: 11,
  },
  {
    minutesAgo: 420,
    author: 12,
    body: '비 그친 뒤라 공기가 깨끗하네요. 창문 열고 환기 중이에요',
    likes: 4,
    scene: 'rain',
  },
  {
    minutesAgo: 600,
    author: 9,
    body: '역삼동 카페 테라스 자리 아직 앉을 만한가요? 오늘 앉아도 될지 궁금해요 ☕',
    likes: 2,
    comments: [[12, 580, '햇볕 들 때는 괜찮아요. 해 지면 추워요']],
  },
  {
    minutesAgo: 900,
    author: 13,
    body: '새벽 러닝 체감 기록 남겨요. 뛰기 시작하면 딱 좋음!',
    likes: 5,
    feeling: 'JUST_RIGHT',
  },
  {
    minutesAgo: 1300,
    author: 10,
    body: '주말에 대치동 쪽은 날씨 어땠나요? 나들이 가려고요',
    likes: 1,
  },
  {
    minutesAgo: 2000,
    author: 'me',
    body: '요즘 날씨 앱보다 여기 체감이 더 정확한 듯 ㅋㅋ',
    likes: 19,
    comments: [[2, 1900, '인정합니다 ㅋㅋ']],
  },
];

/** 다른 동네(관심 동네 등) 글 */
const OTHER_POSTS: SeedPost[] = [
  { minutesAgo: 6, author: 10, body: '여기도 바람 꽤 불어요. 따뜻하게 입고 나오세요', likes: 4, feeling: 'COLD' },
  { minutesAgo: 22, author: 6, body: '오후 하늘 맑아서 한 장 남겨요', likes: 15, scene: 'sky' },
  { minutesAgo: 49, author: 3, body: '공원 단풍 아직 남아 있어요 🍁 산책 추천!', likes: 9, scene: 'park' },
  { minutesAgo: 95, author: 1, body: '아침엔 쌀쌀했는데 지금은 딱 좋네요', likes: 3, feeling: 'JUST_RIGHT' },
  {
    minutesAgo: 240,
    author: 11,
    body: '저녁에 비 소식 있나요? 우산 챙길까요',
    likes: 2,
    comments: [[0, 230, '아직 소식 없어요']],
  },
  { minutesAgo: 700, author: 8, body: '아이들 하원길 외투 챙기세요. 해 지면 확 추워져요', likes: 7 },
];

const PAGE_SIZE = 10;

function bump(tag: TemperatureTagName, delta: number): TemperatureTagName {
  const i = TEMPERATURE_TAGS.indexOf(tag) + delta;
  return TEMPERATURE_TAGS[Math.min(Math.max(i, 0), TEMPERATURE_TAGS.length - 1)];
}

function tagOf(feelsLike: number): TemperatureTagName {
  const limits = [0, 6, 12, 20, 25, 29];
  const index = limits.findIndex((limit) => feelsLike < limit);
  return TEMPERATURE_TAGS[index === -1 ? 6 : index];
}

function round1(value: number): number {
  return Math.round(value * 10) / 10;
}

function noContent(): Response {
  return new Response(null, { status: 204 });
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

function problem(status: number, code: string, detail: string): Response {
  return json({ type: 'about:blank', title: code, status, detail, code }, status);
}

export function createDemoServer(now: number = Date.now()) {
  const ME = { ...ME_SEED };
  const at = (minutesAgo: number) => new Date(now - minutesAgo * MINUTE).toISOString();
  // KST 기준 날짜·시각
  const kst = (time: number) => {
    const d = new Date(time + KST_OFFSET);
    return { date: d.toISOString().slice(0, 10), hour: d.getUTCHours(), month: d.getUTCMonth() };
  };
  const today = kst(now);
  const high = MONTH_HIGH[today.month];
  const low = MONTH_LOW[today.month];
  let seq = 1000;
  const nextId = (prefix: string) => `${prefix}-${++seq}`;

  /** 하루 기온 곡선: 새벽 5시 최저, 오후 3시 최고. 동네마다 조금씩 다르게 */
  const temperatureAt = (hour: number, offset: number) => {
    const sinceLow = (hour - 5 + 24) % 24;
    const rise =
      sinceLow <= 10
        ? 0.5 - 0.5 * Math.cos((sinceLow * Math.PI) / 10)
        : 0.5 + 0.5 * Math.cos(((sinceLow - 10) * Math.PI) / 14);
    return round1(low + (high - low) * rise + offset);
  };

  const places = new Map<string, NeighborhoodResponse>(
    PLACES.map(([id, name]) => [
      id,
      {
        id,
        sidoName: '서울특별시',
        sigunguName: '강남구',
        eupmyeondongName: name,
        fullName: `서울특별시 강남구 ${name}`,
        shortName: name,
        representativePoint: null,
        forecastGrid: null,
        status: 'VALID',
      },
    ]),
  );
  const placeOffset = (id: string) => (PLACES.findIndex(([key]) => key === id) % 3) * 0.4;

  const profile: MyProfile = {
    memberId: 'demo-member',
    nickname: ME_SEED.nickname,
    temperatureSensitivity: 'NORMAL',
    email: null,
    providers: ['KAKAO'],
    nicknameChangeAvailableAt: null,
    termsReagreementRequired: false,
    termsToReagree: [],
  };
  let member = true;
  let interests = [...DEMO_INTEREST_IDS];

  // ── 커뮤니티 ──────────────────────────────────────────────
  type StoredPost = PostSummary & { comments: CommentView[]; likedBy: boolean };
  const posts: StoredPost[] = [];

  const authorOf = (who: number | 'me'): PostAuthor =>
    who === 'me' ? ME : { nickname: NICKNAMES[who % NICKNAMES.length], temperatureSensitivity: SENSITIVITIES[who % 3] };

  const commentOf = (nick: number, minutesAgo: number, body: string, replies: CommentView[] = []): CommentView => ({
    commentId: nextId('c'),
    deleted: false,
    body,
    author: authorOf(nick),
    createdAt: at(minutesAgo),
    edited: false,
    mine: false,
    replies,
  });

  const feelingRecordOf = (tag: TemperatureTagName, author: PostAuthor, recordedAt: string): AttachedFeelingRecord => ({
    recordId: nextId('fr'),
    temperature: tag,
    humidity: 'COMFORTABLE',
    wind: 'LIGHT',
    recordedAt,
    temperatureSensitivity: author.temperatureSensitivity,
  });

  function seed(list: SeedPost[], neighborhoodId: string) {
    const place = places.get(neighborhoodId)!;
    for (const item of list) {
      const author = authorOf(item.author);
      const createdAt = at(item.minutesAgo);
      const comments = (item.comments ?? []).map(([nick, ago, body, replies]) =>
        commentOf(
          nick,
          ago,
          body,
          (replies ?? []).map(([n, a, b]) => commentOf(n, a, b)),
        ),
      );
      const id = nextId('post');
      posts.push({
        postId: id,
        body: item.body,
        images: item.scene ? [demoImage(`${id}-img`, item.scene)] : [],
        neighborhood: { id: place.id, name: place.eupmyeondongName, shortName: place.shortName },
        attachedFeelingRecord: item.feeling ? feelingRecordOf(item.feeling, author, createdAt) : null,
        createdAt,
        edited: false,
        updatedAt: null,
        likeCount: item.likes,
        commentCount: comments.reduce((sum, c) => sum + 1 + c.replies.length, 0),
        author,
        likedByViewer: false,
        mine: item.author === 'me',
        comments,
        likedBy: false,
      });
    }
  }
  seed(HOME_POSTS, DEMO_CURRENT_ID);
  for (const [id] of PLACES.slice(1)) {
    seed(OTHER_POSTS, id);
  }

  const summary = ({ comments: _comments, likedBy, ...post }: StoredPost): PostSummary => ({
    ...post,
    likedByViewer: member ? likedBy : null,
    mine: member ? post.mine : null,
  });
  const visibleComments = (list: CommentView[]): CommentView[] =>
    list.map((comment) => ({
      ...comment,
      mine: member ? comment.mine : null,
      replies: visibleComments(comment.replies),
    }));
  const findPost = (id: string) => posts.find((post) => post.postId === id);
  const countComments = (post: StoredPost) =>
    post.comments.reduce((sum, c) => sum + (c.deleted ? 0 : 1) + c.replies.filter((r) => !r.deleted).length, 0);

  const page = (list: StoredPost[], cursor: string | null, size: number) => {
    const start = cursor ? Number(cursor) : 0;
    const items = list.slice(start, start + size).map(summary);
    return { items, nextCursor: start + size < list.length ? String(start + size) : null };
  };
  const newest = (list: StoredPost[]) => [...list].sort((a, b) => b.createdAt.localeCompare(a.createdAt));

  // ── 체감 기록 · 현장 제보 ───────────────────────────────────
  const myRecords: MyFeelingRecordItem[] = [
    [180, 'SLIGHTLY_COLD'],
    [1500, 'JUST_RIGHT'],
    [2900, 'COLD'],
    [4300, 'SLIGHTLY_COLD'],
    [5800, 'JUST_RIGHT'],
    [7200, 'SLIGHTLY_COLD'],
  ].map(([ago, tag]) => ({
    id: { value: nextId('rec') },
    recordedAt: at(ago as number),
    neighborhoodId: { value: DEMO_CURRENT_ID },
    neighborhoodName: '역삼1동',
    temperatureTag: tag as TemperatureTagName,
    humidityTag: 'COMFORTABLE',
    windTag: 'LIGHT',
    source: 'DIRECT',
    temperatureSensitivity: 'NORMAL',
  }));
  let recentRecord: { item: MyFeelingRecordItem; createdAt: string } | null = null;
  const extraSamples = { count: 0 };

  const reportCounts: Record<FieldReportAxis, Partial<Record<FieldReportKind, number>>> = {
    SKY_PRECIPITATION: { OVERCAST: 6, CLEAR: 2 },
    AIR: { DUST_BAD: 5, AIR_CLEAN: 1 },
  };
  let myReports: FieldReport[] = [];
  const axisOf = (kind: FieldReportKind): FieldReportAxis =>
    kind === 'DUST_BAD' || kind === 'AIR_CLEAN' ? 'AIR' : 'SKY_PRECIPITATION';

  function fieldReportStatus(neighborhoodId: string): FieldReportStatus {
    const isHome = neighborhoodId === DEMO_CURRENT_ID;
    return {
      neighborhoodId: { value: neighborhoodId },
      aggregatedAt: at(1),
      axes: (['SKY_PRECIPITATION', 'AIR'] as const).map((axis) => {
        const counts = Object.entries(isHome ? reportCounts[axis] : { [axis === 'AIR' ? 'AIR_CLEAN' : 'CLEAR']: 2 })
          .filter(([, n]) => (n ?? 0) > 0)
          .map(([kind, n]) => ({ kind: kind as FieldReportKind, reporterCount: n as number }))
          .sort((a, b) => b.reporterCount - a.reporterCount);
        const total = counts.reduce((sum, c) => sum + c.reporterCount, 0);
        return {
          axis,
          reporterCount: total,
          counts,
          majorityKind: total >= 3 && counts[0].reporterCount * 2 > total ? counts[0].kind : null,
          latestReportedAt: total > 0 ? at(4) : null,
        };
      }),
      myReports: member && isHome ? myReports.filter((report) => report.canceledAt === null) : [],
    };
  }

  // ── 홈 ──────────────────────────────────────────────────
  function feelingOf(
    neighborhoodId: string,
    group: TemperatureSensitivity,
    tag: TemperatureTagName,
    basisTime: string,
    timeKind: SensitivityFeeling['timeKind'],
    sampleCount: number,
  ): SensitivityFeeling {
    return {
      neighborhoodId: { value: neighborhoodId },
      temperatureSensitivity: group,
      basisTime,
      timeKind,
      temperatureTag: tag,
      humidityTag: 'COMFORTABLE',
      windTag: 'LIGHT',
      source: 'NEIGHBORHOOD_FEELING',
      sampleCount,
      mappingVersion: null,
      mappingLayer: null,
      weatherBasedTemperatureTag: tag,
      derivedAt: at(2),
    };
  }

  function home(neighborhoodId: string, guestGroup: TemperatureSensitivity | null): HomeView | null {
    const place = places.get(neighborhoodId);
    if (!place) {
      return null;
    }
    const offset = placeOffset(neighborhoodId);
    const isCurrent = member && neighborhoodId === DEMO_CURRENT_ID;
    const group: TemperatureSensitivity = member ? profile.temperatureSensitivity : (guestGroup ?? 'NORMAL');
    const id = { value: neighborhoodId };
    const temperature = temperatureAt(today.hour, offset);
    const feelsLike = round1(temperature - 1.6);
    const night = today.hour < 7 || today.hour >= 18;
    const baseTag = tagOf(feelsLike);
    const ok = <T>(data: T): HomeSection<T> => ({ status: 'OK', data, failure: null });

    const slots: HourlyForecastSlot[] = Array.from({ length: 24 }, (_, i) => {
      const time = now + (i + 1) * 60 * MINUTE;
      const { date, hour } = kst(time);
      const t = temperatureAt(hour, offset);
      const sky: SkyCondition = hour >= 9 && hour <= 14 ? 'MOSTLY_CLOUDY' : 'CLEAR';
      return {
        forecastAt: `${date}T${String(hour).padStart(2, '0')}:00:00+09:00`,
        date,
        hour,
        temperature: Math.round(t),
        feelsLike: Math.round(t - 1.6),
        humidity: 45 + (hour % 5) * 3,
        windSpeed: round1(2 + (hour % 4) * 0.6),
        precipitationProbability: sky === 'CLEAR' ? 0 : 20,
        precipitationType: 'NONE',
        precipitation: null,
        snowfall: null,
        precipitationPhrase: null,
        sky,
        dayNight: hour >= 7 && hour < 18 ? 'DAY' : 'NIGHT',
      };
    });

    const days = Array.from({ length: 10 }, (_, i) => {
      const { date } = kst(now + i * 24 * 60 * MINUTE);
      const swing = [0, 1, -2, -1, 2, 1, -3, 0, 2, -1][i];
      const rainy = i === 3 || i === 7;
      return {
        date,
        minTemperature: low + swing,
        maxTemperature: high + swing,
        amSky: (rainy ? 'OVERCAST' : i % 3 === 1 ? 'MOSTLY_CLOUDY' : 'CLEAR') as SkyCondition,
        pmSky: (rainy ? 'OVERCAST' : i % 2 === 0 ? 'CLEAR' : 'MOSTLY_CLOUDY') as SkyCondition,
        amPrecipitationProbability: rainy ? 60 : 10,
        pmPrecipitationProbability: rainy ? 70 : 20,
        amPrecipitationType: rainy ? ('RAIN' as const) : ('NONE' as const),
        pmPrecipitationType: rainy ? ('RAIN' as const) : ('NONE' as const),
        source: i < 4 ? ('SHORT_TERM' as const) : ('MID_TERM' as const),
        issuedAt: at(90),
      };
    });

    const basisTime = at(5);
    const samples = (n: number) => n + (neighborhoodId === DEMO_CURRENT_ID ? extraSamples.count : 0);
    const groups = (['HEAT_SENSITIVE', 'NORMAL', 'COLD_SENSITIVE'] as const).map((g, i) =>
      feelingOf(neighborhoodId, g, bump(baseTag, GROUP_OFFSET[g]), basisTime, 'CURRENT', samples([17, 41, 23][i])),
    );
    const mine = groups.find((g) => g.temperatureSensitivity === group)!;
    const level = TEMPERATURE_TAGS.indexOf(mine.temperatureTag) + 1;
    const yesterdayTag = bump(mine.temperatureTag, 1);

    const neighborhoodPosts = newest(posts.filter((post) => post.neighborhood.id === neighborhoodId));
    const popular = [...neighborhoodPosts]
      .filter((post) => now - Date.parse(post.createdAt) < 24 * 60 * MINUTE)
      .sort((a, b) => b.likeCount - a.likeCount)
      .slice(0, 3)
      .map(summary);
    const status = fieldReportStatus(neighborhoodId);
    const isHome = neighborhoodId === DEMO_CURRENT_ID;

    return {
      neighborhood: {
        id: place.id,
        sidoName: place.sidoName,
        sigunguName: place.sigunguName,
        eupmyeondongName: place.eupmyeondongName,
        fullName: place.fullName,
        shortName: place.shortName,
        current: isCurrent,
      },
      currentWeather: ok({
        neighborhoodId: id,
        observedAt: at(12),
        temperature,
        humidity: 48,
        windSpeed: 3.4,
        precipitation: { lower: 0, upper: 0, lowerExclusive: false, unit: 'mm', label: '없음' },
        precipitationType: 'NONE',
        precipitationPhrase: null,
        sky: 'CLEAR',
        feelsLike,
        sources: { TEMPERATURE: 'OBSERVATION', SKY: 'FORECAST', AIR_QUALITY: 'MEASUREMENT', UV: 'MEASUREMENT' },
        weatherFreshness: 'FRESH',
        airQuality: {
          stationName: '강남구',
          measuredAt: at(18),
          pm10: 42,
          pm25: 21,
          pm10Grade: 'MODERATE',
          pm25Grade: 'MODERATE',
          combinedGrade: 'MODERATE',
          ozoneGrade: 'GOOD',
          freshness: 'FRESH',
        },
        uv: night
          ? null
          : {
              issuedAt: at(60),
              value: 3,
              grade: 'MODERATE',
              gradeLabel: '보통',
              guidance: '모자나 선글라스를 챙기세요',
              freshness: 'FRESH',
            },
        dayNight: night ? 'NIGHT' : 'DAY',
      }),
      hourlyForecast: ok({ neighborhoodId: id, issuedAt: at(40), freshness: 'FRESH', slots, nextPrecipitation: null }),
      dailyForecast: ok({ neighborhoodId: id, freshness: 'FRESH', days }),
      personal: { highlightedGroup: group, basisGroup: group, selectionSuggested: false },
      sensitivityFeelings: ok({ neighborhoodId: id, basisTime, groups }),
      feedbackStatus: member
        ? ok({
            available: isCurrent,
            recentRecord:
              isCurrent && recentRecord
                ? {
                    id: recentRecord.item.id,
                    source: recentRecord.item.source,
                    temperatureTag: recentRecord.item.temperatureTag,
                    recordedAt: recentRecord.item.recordedAt,
                    createdAt: recentRecord.createdAt,
                    overwritableUntil: new Date(Date.parse(recentRecord.createdAt) + 30 * MINUTE).toISOString(),
                  }
                : null,
          })
        : null,
      outfit: ok({ temperatureLevel: level, precipitation: 'NONE', outfit: OUTFITS[level - 1], tableVersion: 'demo' }),
      yesterdayComparison: ok({
        neighborhoodId: id,
        temperatureSensitivity: group,
        comparable: true,
        levelDifference: -1,
        phrase: '어제보다 조금 더 쌀쌀해요',
        today: mine,
        yesterday: feelingOf(neighborhoodId, group, yesterdayTag, at(24 * 60), 'PAST', 36),
      }),
      departureOutfit: null,
      popularPosts: popular.length ? ok(popular) : { status: 'EMPTY', data: null, failure: null },
      divergence: isHome
        ? ok({
            neighborhoodId: id,
            judgedAt: at(3),
            axes: [
              {
                axis: 'SKY',
                badge: true,
                basisValue: 'CLEAR',
                basisLabel: '맑음',
                basisSource: 'FORECAST',
                basisTime: at(40),
                residentKind: 'OVERCAST',
                reporterCount: status.axes[0].reporterCount,
                majorityCount: status.axes[0].counts[0]?.reporterCount ?? 0,
              },
              {
                axis: 'AIR',
                badge: true,
                basisValue: 'MODERATE',
                basisLabel: '보통',
                basisSource: 'MEASUREMENT',
                basisTime: at(18),
                residentKind: 'DUST_BAD',
                reporterCount: status.axes[1].reporterCount,
                majorityCount: status.axes[1].counts[0]?.reporterCount ?? 0,
              },
            ],
          })
        : { status: 'EMPTY', data: null, failure: null },
      fieldReports: ok(status),
      calibrationOffer: member ? { status: 'EMPTY', data: null, failure: null } : null,
    };
  }

  // ── 쓰기 결과 ───────────────────────────────────────────
  function saveFeeling(
    temperature: TemperatureTagName,
    humidity: HumidityTagName | null,
    wind: WindTagName | null,
    source: FeelingRecordSource,
  ): FeelingRecordResult {
    const createdAt = new Date().toISOString();
    const item: MyFeelingRecordItem = {
      id: { value: nextId('rec') },
      recordedAt: createdAt,
      neighborhoodId: { value: DEMO_CURRENT_ID },
      neighborhoodName: '역삼1동',
      temperatureTag: temperature,
      humidityTag: humidity,
      windTag: wind,
      source,
      temperatureSensitivity: profile.temperatureSensitivity,
    };
    const overwritten = recentRecord !== null;
    if (recentRecord) {
      myRecords.splice(myRecords.indexOf(recentRecord.item), 1);
    } else {
      extraSamples.count += 1;
    }
    myRecords.unshift(item);
    recentRecord = { item, createdAt };
    return {
      recordId: item.id.value,
      neighborhoodId: DEMO_CURRENT_ID,
      createdAt,
      recordedAt: createdAt,
      temperatureTag: temperature,
      humidityTag: humidity,
      windTag: wind,
      temperatureSensitivity: profile.temperatureSensitivity,
      source,
      snapshotSecured: true,
      overwritten,
      overwritableUntil: new Date(Date.parse(createdAt) + 30 * MINUTE).toISOString(),
    };
  }

  function myFeelingRecords(): MyFeelingRecords {
    const distribution: Partial<Record<TemperatureTagName, number>> = {};
    for (const record of myRecords) {
      distribution[record.temperatureTag] = (distribution[record.temperatureTag] ?? 0) + 1;
    }
    return {
      records: myRecords,
      pattern: {
        recordCount: myRecords.length,
        temperatureDistribution: distribution,
        tendency: 'FEELS_COLDER',
        tendencyPhrase: '이웃보다 조금 더 춥게 느끼는 편이에요',
        averageDifference: -0.6,
        tendencySampleCount: myRecords.length,
      },
    };
  }

  const tokens = () => ({
    accessToken: 'demo-access',
    accessTokenExpiresAt: new Date(now + 365 * 24 * 60 * MINUTE).toISOString(),
    refreshToken: 'demo-refresh',
    refreshTokenExpiresAt: new Date(now + 365 * 24 * 60 * MINUTE).toISOString(),
  });

  const interestList = () => interests.map((id) => places.get(id)!).filter(Boolean);
  const currentPlace = () => places.get(DEMO_CURRENT_ID)!;

  // ── 라우팅 ──────────────────────────────────────────────
  type Body = Record<string, unknown>;
  type Route = [method: string, pattern: RegExp, handle: (m: string[], body: Body, q: URLSearchParams) => Response];
  const routes: Route[] = [
    [
      'GET',
      /^\/api\/v1\/home$/,
      (_m, _b, q) => {
        const id = q.get('neighborhoodId') ?? (member ? DEMO_CURRENT_ID : null);
        const view = id ? home(id, q.get('temperatureSensitivity') as TemperatureSensitivity | null) : null;
        return view ? json(view) : problem(400, 'VIEWING_NEIGHBORHOOD_UNRESOLVED', '보는 동네를 정할 수 없어요');
      },
    ],
    ['GET', /^\/api\/v1\/feeling\/tags$/, () => json(TAG_DEFINITIONS)],
    ['GET', /^\/api\/v1\/terms$/, () => json([])],
    ['POST', /^\/api\/v1\/auth\/refresh$/, () => json(tokens())],
    [
      'POST',
      /^\/api\/v1\/auth\/logout$/,
      () => {
        member = false;
        return noContent();
      },
    ],
    [
      'POST',
      /^\/api\/v1\/(auth\/login|dev\/login|signup)$/,
      () => {
        member = true;
        return json({
          registered: true,
          tokens: tokens(),
          member: { ...profile, termsToReagree: [] },
          signupTicket: null,
          terms: null,
        });
      },
    ],
    ['GET', /^\/api\/v1\/members\/me$/, () => json(profile)],
    [
      'DELETE',
      /^\/api\/v1\/members\/me$/,
      () => {
        member = false;
        return noContent();
      },
    ],
    ['POST', /^\/api\/v1\/members\/me\/terms-agreements$/, () => json(profile)],
    [
      'PUT',
      /^\/api\/v1\/members\/me\/temperature-sensitivity$/,
      (_m, body) => {
        profile.temperatureSensitivity = body.temperatureSensitivity as TemperatureSensitivity;
        ME.temperatureSensitivity = profile.temperatureSensitivity;
        return json(profile);
      },
    ],
    [
      'PUT',
      /^\/api\/v1\/members\/me\/nickname$/,
      (_m, body) => {
        profile.nickname = String(body.nickname);
        ME.nickname = profile.nickname;
        return json({ nickname: profile.nickname, nextChangeAvailableAt: null });
      },
    ],
    [
      'DELETE',
      /^\/api\/v1\/members\/me\/login-accounts\/\w+$/,
      () => problem(409, 'LAST_LOGIN_ACCOUNT', '마지막 로그인 계정은 해제할 수 없어요'),
    ],
    ['GET', /^\/api\/v1\/members\/me\/calibration-offer$/, () => json({ offer: null })],
    ['GET', /^\/api\/v1\/members\/me\/feeling-records$/, () => json(myFeelingRecords())],
    [
      'GET',
      /^\/api\/v1\/members\/me\/posts$/,
      (_m, _b, q) =>
        json(page(newest(posts.filter((post) => post.mine)), q.get('cursor'), Number(q.get('size')) || PAGE_SIZE)),
    ],
    [
      'PUT',
      /^\/api\/v1\/members\/me\/current-neighborhood$/,
      (_m, body) => {
        // 좌표 대신 기기가 판정하고 사용자가 확인한 동네 ID를 받는다(ADR 0009). 데모 동네 밖이면 거부.
        const place = places.get(String(body.neighborhoodId));
        return place
          ? json({ neighborhood: place, confirmedAt: new Date().toISOString() })
          : problem(422, 'CURRENT_NEIGHBORHOOD_UNVERIFIED', '현재 동네를 확인하지 못했어요');
      },
    ],
    ['GET', /^\/api\/v1\/members\/me\/interest-neighborhoods$/, () => json(interestList())],
    [
      'POST',
      /^\/api\/v1\/members\/me\/interest-neighborhoods$/,
      (_m, body) => {
        const id = String(body.neighborhoodId);
        if (places.has(id) && !interests.includes(id)) {
          interests.push(id);
        }
        return json(interestList());
      },
    ],
    [
      'PUT',
      /^\/api\/v1\/members\/me\/interest-neighborhoods\/order$/,
      (_m, body) => {
        interests = (body.neighborhoodIds as string[]).filter((id) => interests.includes(id));
        return json(interestList());
      },
    ],
    [
      'POST',
      /^\/api\/v1\/members\/me\/interest-neighborhoods\/merge$/,
      () => json({ interests: interestList(), skipped: [], discarded: [] }),
    ],
    [
      'DELETE',
      /^\/api\/v1\/members\/me\/interest-neighborhoods\/([^/]+)$/,
      ([, id]) => {
        interests = interests.filter((item) => item !== decodeURIComponent(id));
        return json(interestList());
      },
    ],
    [
      'GET',
      /^\/api\/v1\/neighborhoods\/search$/,
      (_m, _b, q) => {
        const keyword = (q.get('q') ?? '').trim();
        return json([...places.values()].filter((place) => keyword && place.fullName.includes(keyword)));
      },
    ],
    [
      'GET',
      /^\/api\/v1\/neighborhoods$/,
      (_m, _b, q) =>
        json(
          q
            .getAll('ids')
            .map((id) => places.get(id))
            .filter(Boolean),
        ),
    ],
    [
      'GET',
      /^\/api\/v1\/neighborhoods\/([^/]+)$/,
      ([, id]) => {
        const place = places.get(decodeURIComponent(id));
        return place ? json(place) : problem(404, 'NEIGHBORHOOD_NOT_FOUND', '없는 동네예요');
      },
    ],
    [
      'GET',
      /^\/api\/v1\/community\/neighborhoods\/([^/]+)\/posts$/,
      ([, id], _b, q) =>
        json(
          page(
            newest(posts.filter((post) => post.neighborhood.id === decodeURIComponent(id))),
            q.get('cursor'),
            Number(q.get('size')) || PAGE_SIZE,
          ),
        ),
    ],
    [
      'POST',
      /^\/api\/v1\/community\/posts$/,
      (_m, body) => {
        const place = currentPlace();
        const recordId = body.feelingRecordId as string | null;
        const record = recordId ? myRecords.find((item) => item.id.value === recordId) : undefined;
        const createdAt = new Date().toISOString();
        const id = nextId('post');
        posts.push({
          postId: id,
          body: String(body.body),
          images: [],
          neighborhood: { id: place.id, name: place.eupmyeondongName, shortName: place.shortName },
          attachedFeelingRecord: record
            ? {
                recordId: record.id.value,
                temperature: record.temperatureTag,
                humidity: record.humidityTag,
                wind: record.windTag,
                recordedAt: record.recordedAt,
                temperatureSensitivity: record.temperatureSensitivity,
              }
            : null,
          createdAt,
          edited: false,
          updatedAt: null,
          likeCount: 0,
          commentCount: 0,
          author: { ...ME },
          likedByViewer: false,
          mine: true,
          comments: [],
          likedBy: false,
        });
        return json({ postId: id });
      },
    ],
    [
      'GET',
      /^\/api\/v1\/community\/posts\/([^/]+)$/,
      ([, id]) => {
        const post = findPost(decodeURIComponent(id));
        return post
          ? json({ post: summary(post), comments: { items: visibleComments(post.comments), nextCursor: null } })
          : problem(404, 'POST_NOT_FOUND', '삭제된 글이에요');
      },
    ],
    [
      'GET',
      /^\/api\/v1\/community\/posts\/([^/]+)\/comments$/,
      ([, id]) => json({ items: visibleComments(findPost(decodeURIComponent(id))?.comments ?? []), nextCursor: null }),
    ],
    [
      'PUT',
      /^\/api\/v1\/community\/posts\/([^/]+)$/,
      ([, id], body) => {
        const post = findPost(decodeURIComponent(id));
        if (!post) {
          return problem(404, 'POST_NOT_FOUND', '삭제된 글이에요');
        }
        const keep = new Set(body.imageIds as string[]);
        post.body = String(body.body);
        post.images = post.images.filter((image) => keep.has(image.imageId));
        if (body.detachFeelingRecord) {
          post.attachedFeelingRecord = null;
        }
        post.edited = true;
        post.updatedAt = new Date().toISOString();
        return json(summary(post));
      },
    ],
    [
      'DELETE',
      /^\/api\/v1\/community\/posts\/([^/]+)$/,
      ([, id]) => {
        const index = posts.findIndex((post) => post.postId === decodeURIComponent(id));
        if (index >= 0) {
          posts.splice(index, 1);
        }
        return noContent();
      },
    ],
    [
      'PUT',
      /^\/api\/v1\/community\/posts\/([^/]+)\/like$/,
      ([, id], body) => {
        const post = findPost(decodeURIComponent(id));
        if (!post) {
          return problem(404, 'POST_NOT_FOUND', '삭제된 글이에요');
        }
        const liked = Boolean(body.liked);
        if (liked !== post.likedBy) {
          post.likeCount += liked ? 1 : -1;
          post.likedBy = liked;
        }
        return json({ liked, likeCount: post.likeCount });
      },
    ],
    [
      'POST',
      /^\/api\/v1\/community\/posts\/([^/]+)\/comments$/,
      ([, id], body) => {
        const post = findPost(decodeURIComponent(id));
        if (!post) {
          return problem(404, 'POST_NOT_FOUND', '삭제된 글이에요');
        }
        const comment = {
          ...commentOf(0, 0, String(body.body)),
          author: { ...ME },
          createdAt: new Date().toISOString(),
          mine: true,
        };
        post.comments.push(comment);
        post.commentCount = countComments(post);
        return json({ commentId: comment.commentId });
      },
    ],
    [
      'POST',
      /^\/api\/v1\/community\/comments\/([^/]+)\/replies$/,
      ([, id], body) => {
        const parentId = decodeURIComponent(id);
        const post = posts.find((item) => item.comments.some((c) => c.commentId === parentId));
        const parent = post?.comments.find((c) => c.commentId === parentId);
        if (!post || !parent) {
          return problem(404, 'COMMENT_NOT_FOUND', '삭제된 댓글이에요');
        }
        const reply = {
          ...commentOf(0, 0, String(body.body)),
          author: { ...ME },
          createdAt: new Date().toISOString(),
          mine: true,
        };
        parent.replies.push(reply);
        post.commentCount = countComments(post);
        return json({ commentId: reply.commentId });
      },
    ],
    [
      'DELETE',
      /^\/api\/v1\/community\/comments\/([^/]+)$/,
      ([, id]) => {
        const target = decodeURIComponent(id);
        for (const post of posts) {
          for (const comment of [...post.comments, ...post.comments.flatMap((c) => c.replies)]) {
            if (comment.commentId === target) {
              Object.assign(comment, { deleted: true, body: null, author: null });
              post.commentCount = countComments(post);
            }
          }
        }
        return noContent();
      },
    ],
    [
      'POST',
      /^\/api\/v1\/community\/images$/,
      () => problem(400, 'DEMO_IMAGE_UNSUPPORTED', '데모에서는 사진을 올릴 수 없어요'),
    ],
    [
      'POST',
      /^\/api\/v1\/feeling\/records$/,
      (_m, body) =>
        json(
          saveFeeling(
            body.temperature as TemperatureTagName,
            (body.humidity as HumidityTagName | null) ?? null,
            (body.wind as WindTagName | null) ?? null,
            'DIRECT',
          ),
        ),
    ],
    [
      'POST',
      /^\/api\/v1\/feeling\/feedbacks$/,
      (_m, body) => {
        const shown = body.shown as { temperatureTag: TemperatureTagName };
        const delta = body.answer === 'COLDER' ? -1 : body.answer === 'HOTTER' ? 1 : 0;
        return json(saveFeeling(bump(shown.temperatureTag, delta), null, null, 'FEEDBACK'));
      },
    ],
    [
      'GET',
      /^\/api\/v1\/weather\/neighborhoods\/([^/]+)\/field-reports$/,
      ([, id]) => json(fieldReportStatus(decodeURIComponent(id))),
    ],
    [
      'POST',
      /^\/api\/v1\/weather\/field-reports$/,
      (_m, body) => {
        const kind = body.kind as FieldReportKind;
        const axis = axisOf(kind);
        const previous = myReports.find((report) => report.axis === axis && report.canceledAt === null);
        if (previous) {
          previous.canceledAt = new Date().toISOString();
          reportCounts[axis][previous.kind] = (reportCounts[axis][previous.kind] ?? 1) - 1;
        }
        const reportedAt = new Date().toISOString();
        const report: FieldReport = {
          reportId: nextId('report'),
          neighborhoodId: { value: DEMO_CURRENT_ID },
          axis,
          kind,
          reportedAt,
          cancelableUntil: new Date(Date.parse(reportedAt) + 10 * MINUTE).toISOString(),
          canceledAt: null,
        };
        myReports.push(report);
        reportCounts[axis][kind] = (reportCounts[axis][kind] ?? 0) + 1;
        return json({ report, status: fieldReportStatus(DEMO_CURRENT_ID) });
      },
    ],
    [
      'POST',
      /^\/api\/v1\/weather\/field-reports\/([^/]+)\/cancel$/,
      ([, id]) => {
        const report = myReports.find((item) => item.reportId === decodeURIComponent(id));
        if (!report) {
          return problem(404, 'FIELD_REPORT_NOT_FOUND', '없는 제보예요');
        }
        if (report.canceledAt === null) {
          report.canceledAt = new Date().toISOString();
          reportCounts[report.axis][report.kind] = (reportCounts[report.axis][report.kind] ?? 1) - 1;
        }
        return json({ report, status: fieldReportStatus(DEMO_CURRENT_ID) });
      },
    ],
  ];

  async function demoFetch(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
    const url = new URL(String(input), 'http://demo.local');
    const method = (init?.method ?? 'GET').toUpperCase();
    const body = typeof init?.body === 'string' ? (JSON.parse(init.body) as Body) : {};
    // 진짜 서버처럼 아주 잠깐 기다려 로딩 표시가 깜빡이는 정도만 보이게 한다.
    await new Promise((resolve) => setTimeout(resolve, 120));
    for (const [routeMethod, pattern, handle] of routes) {
      const match = routeMethod === method ? url.pathname.match(pattern) : null;
      if (match) {
        return handle([...match], body, url.searchParams);
      }
    }
    return problem(404, 'DEMO_NOT_SUPPORTED', '데모에서는 아직 지원하지 않는 기능이에요');
  }

  return { fetch: demoFetch };
}
