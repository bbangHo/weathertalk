// 홈 조합 응답 형식 (07 §1.2·§10~12, backend home/HomeView·HomeSection·ViewingNeighborhood·PersonalBasis,
// weather/CurrentObservation·HourlyForecast·DailyForecast). 시각(Instant)은 ISO 문자열, 날짜(LocalDate)는 YYYY-MM-DD.
// 값이 없는 항목은 null이다 — 앱은 기본값으로 채우지 않는다(07 §5 값 없음 표시).
import type { PostSummary } from '@/community/types';
import type {
  CalibrationOffer,
  FeedbackStatus,
  SensitivityFeeling,
  SensitivityFeelings,
  YesterdayComparison,
} from '@/feeling/types';
import type { TemperatureSensitivity } from '@/storage';

export type SectionStatus = 'OK' | 'EMPTY' | 'FAILED';
export type SectionFailure = 'TIMEOUT' | 'ERROR' | 'PREREQUISITE_FAILED';

/** 섹션 하나. data는 정상·데이터 없음일 때 모듈 응답 그대로, 실패면 null */
export type HomeSection<T> = {
  status: SectionStatus;
  data: T | null;
  failure: SectionFailure | null;
};

/** 백엔드 Ids.NeighborhoodId 직렬화 형태 */
export type NeighborhoodIdValue = { value: string };

export type Freshness = 'FRESH' | 'DELAYED' | 'NONE';
export type DayNight = 'DAY' | 'NIGHT';
export type SkyCondition = 'CLEAR' | 'MOSTLY_CLOUDY' | 'OVERCAST';
export type PrecipitationType = 'NONE' | 'RAIN' | 'RAIN_SNOW' | 'SNOW' | 'SHOWER';
export type AirGrade = 'GOOD' | 'MODERATE' | 'BAD' | 'VERY_BAD';
export type UvGrade = 'LOW' | 'MODERATE' | 'HIGH' | 'VERY_HIGH' | 'EXTREME';
export type ValueSource = 'OBSERVATION' | 'FORECAST' | 'MEASUREMENT';
export type ObservationItem =
  | 'TEMPERATURE'
  | 'HUMIDITY'
  | 'WIND_SPEED'
  | 'PRECIPITATION'
  | 'PRECIPITATION_TYPE'
  | 'SKY'
  | 'FEELS_LIKE'
  | 'AIR_QUALITY'
  | 'UV';
export type DailyForecastSource = 'SHORT_TERM' | 'MID_TERM';

/** 강수량·신적설 구간. 표시는 label 그대로("1mm 미만") */
export type PrecipitationAmount = {
  lower: number;
  upper: number | null;
  lowerExclusive: boolean;
  unit: string;
  label: string;
};

export type AirQuality = {
  stationName: string | null;
  measuredAt: string | null;
  pm10: number | null;
  pm25: number | null;
  pm10Grade: AirGrade | null;
  pm25Grade: AirGrade | null;
  combinedGrade: AirGrade | null;
  ozoneGrade: AirGrade | null;
  freshness: Freshness;
};

export type UvIndex = {
  issuedAt: string | null;
  value: number | null;
  grade: UvGrade | null;
  /** 기상청 공식 등급 이름 또는 '정보 없음' */
  gradeLabel: string;
  guidance: string | null;
  freshness: Freshness;
};

export type CurrentObservation = {
  neighborhoodId: NeighborhoodIdValue;
  observedAt: string | null;
  temperature: number | null;
  humidity: number | null;
  windSpeed: number | null;
  precipitation: PrecipitationAmount | null;
  precipitationType: PrecipitationType | null;
  precipitationPhrase: string | null;
  sky: SkyCondition | null;
  feelsLike: number | null;
  sources: Partial<Record<ObservationItem, ValueSource>>;
  weatherFreshness: Freshness;
  airQuality: AirQuality | null;
  /** 밤이면 null */
  uv: UvIndex | null;
  dayNight: DayNight | null;
};

export type HourlyForecastSlot = {
  forecastAt: string;
  date: string;
  hour: number;
  temperature: number | null;
  feelsLike: number | null;
  humidity: number | null;
  windSpeed: number | null;
  precipitationProbability: number | null;
  precipitationType: PrecipitationType | null;
  precipitation: PrecipitationAmount | null;
  snowfall: PrecipitationAmount | null;
  precipitationPhrase: string | null;
  sky: SkyCondition | null;
  dayNight: DayNight | null;
};

export type NextPrecipitation = {
  startsAt: string;
  type: PrecipitationType;
  phrase: string | null;
};

export type HourlyForecast = {
  neighborhoodId: NeighborhoodIdValue;
  issuedAt: string | null;
  freshness: Freshness;
  slots: HourlyForecastSlot[];
  nextPrecipitation: NextPrecipitation | null;
};

export type DailyForecastDay = {
  date: string;
  minTemperature: number | null;
  maxTemperature: number | null;
  amSky: SkyCondition | null;
  pmSky: SkyCondition | null;
  amPrecipitationProbability: number | null;
  pmPrecipitationProbability: number | null;
  amPrecipitationType: PrecipitationType | null;
  pmPrecipitationType: PrecipitationType | null;
  /** 어느 원천도 덮지 않는 날은 null(빈 날) */
  source: DailyForecastSource | null;
  issuedAt: string | null;
};

export type DailyForecast = {
  neighborhoodId: NeighborhoodIdValue;
  freshness: Freshness;
  days: DailyForecastDay[];
};

/** 보는 동네. current는 회원의 현재 동네 여부이며 비회원은 항상 false(앱이 안다). */
export type ViewingNeighborhood = {
  id: string;
  sidoName: string;
  sigunguName: string;
  eupmyeondongName: string;
  fullName: string;
  shortName: string;
  current: boolean;
};

export type PersonalBasis = {
  highlightedGroup: TemperatureSensitivity | null;
  basisGroup: TemperatureSensitivity;
  selectionSuggested: boolean;
};

/** outfit OutfitRecommendation·Outfit (옷 추천). 빈 목록은 "없음" */
export type OutfitPrecipitation = 'NONE' | 'RAIN' | 'SNOW';
export type Outfit = { outer: string[]; top: string[]; bottom: string[]; accessories: string[]; guide: string | null };
export type OutfitRecommendation = {
  temperatureLevel: number;
  precipitation: OutfitPrecipitation | null;
  outfit: Outfit;
  tableVersion: string;
};

/** home DepartureOutfit: 외출 시각별 기준 그룹 체감과 외출 시각 옷 추천 */
export type DepartureOutfit = {
  departures: { departureAt: string; feeling: SensitivityFeeling }[];
  recommendation: {
    recommendation: OutfitRecommendation;
    layeringAdvised: boolean;
    layeringGuide: string | null;
    basisDepartureAt: string;
    basisTemperatureLevel: number;
  } | null;
};

/**
 * 홈 조합 응답. 차이 배지·현장 제보 현황은 A4가 자기 형식을 정할 때까지 unknown으로 둔다. null인 섹션은 조회하지 않은 것(생략)이다.
 */
export type HomeView = {
  neighborhood: ViewingNeighborhood;
  currentWeather: HomeSection<CurrentObservation>;
  hourlyForecast: HomeSection<HourlyForecast>;
  dailyForecast: HomeSection<DailyForecast>;
  personal: PersonalBasis;
  sensitivityFeelings: HomeSection<SensitivityFeelings>;
  /** 비회원은 null */
  feedbackStatus: HomeSection<FeedbackStatus> | null;
  outfit: HomeSection<OutfitRecommendation>;
  yesterdayComparison: HomeSection<YesterdayComparison>;
  /** 기기 헤더가 없으면 null */
  departureOutfit: HomeSection<DepartureOutfit> | null;
  /** 동네 인기글(community 선정 그대로). 0건이면 EMPTY — 최신글로 채우지 않는다 */
  popularPosts: HomeSection<PostSummary[]>;
  divergence: HomeSection<unknown>;
  fieldReports: HomeSection<unknown>;
  /** 비회원은 null */
  calibrationOffer: HomeSection<CalibrationOffer> | null;
};
