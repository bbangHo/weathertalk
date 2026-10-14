// 홈 응답 테스트 데이터 (backend HomeView 형식)
import type { HomeSection, HomeView, HourlyForecastSlot } from '@/home/types';
import type { NeighborhoodResponse } from '@/neighborhood/types';

export function neighborhoodResponse(id: string, eupmyeondong: string): NeighborhoodResponse {
  return {
    id,
    sidoName: '서울특별시',
    sigunguName: '강남구',
    eupmyeondongName: eupmyeondong,
    fullName: `서울특별시 강남구 ${eupmyeondong}`,
    shortName: eupmyeondong,
    representativePoint: { latitude: 37.5, longitude: 127.03 },
    forecastGrid: { nx: 61, ny: 125 },
    status: 'VALID',
  };
}

export function slot(date: string, hour: number, temperature: number): HourlyForecastSlot {
  return {
    forecastAt: `${date}T${String(hour).padStart(2, '0')}:00:00+09:00`,
    date,
    hour,
    temperature,
    feelsLike: temperature,
    humidity: 50,
    windSpeed: 1,
    precipitationProbability: 10,
    precipitationType: 'NONE',
    precipitation: null,
    snowfall: null,
    precipitationPhrase: null,
    sky: 'CLEAR',
    dayNight: 'DAY',
  };
}

const omitted: HomeSection<unknown> = { status: 'EMPTY', data: null, failure: null };

export function homeView(overrides: Partial<HomeView> = {}): HomeView {
  return {
    neighborhood: {
      id: 'N1',
      sidoName: '서울특별시',
      sigunguName: '강남구',
      eupmyeondongName: '역삼1동',
      fullName: '서울특별시 강남구 역삼1동',
      shortName: '역삼1동',
      current: false,
    },
    currentWeather: {
      status: 'OK',
      failure: null,
      data: {
        neighborhoodId: { value: 'N1' },
        observedAt: '2026-10-15T00:00:00Z',
        temperature: 18.04,
        humidity: 55,
        windSpeed: 2.3,
        precipitation: { lower: 0, upper: 0, lowerExclusive: false, unit: 'mm', label: '없음' },
        precipitationType: 'NONE',
        precipitationPhrase: null,
        sky: 'CLEAR',
        feelsLike: 17.2,
        sources: { TEMPERATURE: 'OBSERVATION', AIR_QUALITY: 'MEASUREMENT', UV: 'MEASUREMENT' },
        weatherFreshness: 'FRESH',
        airQuality: {
          stationName: '강남구',
          measuredAt: '2026-10-15T00:00:00Z',
          pm10: 35,
          pm25: null,
          pm10Grade: 'MODERATE',
          pm25Grade: null,
          combinedGrade: 'MODERATE',
          ozoneGrade: null,
          freshness: 'FRESH',
        },
        uv: {
          issuedAt: '2026-10-15T00:00:00Z',
          value: 4,
          grade: 'MODERATE',
          gradeLabel: '보통',
          guidance: '모자, 선글라스 이용',
          freshness: 'FRESH',
        },
        dayNight: 'DAY',
      },
    },
    hourlyForecast: {
      status: 'OK',
      failure: null,
      data: {
        neighborhoodId: { value: 'N1' },
        issuedAt: '2026-10-14T23:00:00Z',
        freshness: 'FRESH',
        slots: [slot('2026-10-15', 22, 15), slot('2026-10-15', 23, 14), slot('2026-10-16', 0, 13)],
        nextPrecipitation: null,
      },
    },
    dailyForecast: {
      status: 'OK',
      failure: null,
      data: {
        neighborhoodId: { value: 'N1' },
        freshness: 'FRESH',
        days: [
          {
            date: '2026-10-15',
            minTemperature: 10,
            maxTemperature: 20,
            amSky: 'CLEAR',
            pmSky: 'MOSTLY_CLOUDY',
            amPrecipitationProbability: 0,
            pmPrecipitationProbability: 20,
            amPrecipitationType: 'NONE',
            pmPrecipitationType: 'NONE',
            source: 'SHORT_TERM',
            issuedAt: '2026-10-14T23:00:00Z',
          },
        ],
      },
    },
    personal: { highlightedGroup: null, basisGroup: 'NORMAL', selectionSuggested: true },
    sensitivityFeelings: omitted,
    feedbackStatus: null,
    outfit: omitted,
    yesterdayComparison: omitted,
    departureOutfit: null,
    popularPosts: omitted,
    divergence: omitted,
    fieldReports: omitted,
    calibrationOffer: null,
    ...overrides,
  };
}
