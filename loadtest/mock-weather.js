// 부하테스트 C 모드용 기상청·에어코리아 모의 서버. 외부 호출 0건으로 모든 동네의 날씨를 채운다.
// 응답 모양은 백엔드 테스트의 WeatherStubs(backend/src/test/.../weather/WeatherStubs.java)와 같다.
// 값은 요청 파라미터(격자·발표 시각)에서 만들어 내서 어느 격자·시각이든 응답한다. 인증키는 확인하지 않는다.
//   node loadtest/mock-weather.js [포트=18090] [지연ms=0]
//   백엔드: WEATHER_SOURCE_KMA_BASEURL=http://localhost:18090/kma, WEATHER_SOURCE_AIRKOREA_BASEURL=http://localhost:18090/air
// 지연을 주면 실제 기상청처럼 느린 원천에서 수집 대기(weather.collect.wait-timeout)가 어떻게 보이는지 볼 수 있다.
const assert = require('assert');
const http = require('http');

const PORT = Number(process.argv[2] || 18090);
const DELAY_MS = Number(process.argv[3] || 0);
const counts = {};

const pad = (n) => String(n).padStart(2, '0');
// 'yyyyMMdd' + 'HHmm' (KST) ↔ 시각 계산은 UTC로 9시간 밀어 둔 Date로 한다.
const parseKst = (date, time) => new Date(Date.UTC(+date.slice(0, 4), +date.slice(4, 6) - 1, +date.slice(6, 8),
  +(time || '0000').slice(0, 2), +(time || '0000').slice(2, 4)));
const ymd = (d) => `${d.getUTCFullYear()}${pad(d.getUTCMonth() + 1)}${pad(d.getUTCDate())}`;
const hm = (d) => `${pad(d.getUTCHours())}${pad(d.getUTCMinutes())}`;
const nowKst = () => new Date(Date.now() + 9 * 3600e3);

// 격자마다 조금씩 다른 기온(같은 격자는 늘 같은 값).
const baseTemp = (nx, ny) => 8 + ((Number(nx) * 7 + Number(ny) * 13) % 10);

const kma = (items) => ({
  response: {
    header: { resultCode: '00', resultMsg: 'NORMAL_SERVICE' },
    body: { dataType: 'JSON', items: { item: items }, pageNo: 1, numOfRows: items.length, totalCount: items.length },
  },
});
const air = (items) => ({
  response: {
    header: { resultCode: '00', resultMsg: 'NORMAL_CODE' },
    body: { totalCount: items.length, items, pageNo: 1, numOfRows: Math.max(items.length, 1) },
  },
});

function observation(q) {
  const t = baseTemp(q.nx, q.ny);
  const values = { T1H: String(t), REH: '60', WSD: '2.0', RN1: '0', PTY: '0' };
  return kma(Object.entries(values).map(([category, obsrValue]) => ({
    baseDate: q.base_date, baseTime: q.base_time, category, nx: +q.nx, ny: +q.ny, obsrValue,
  })));
}

// 발표 시각 + 1시간부터 72칸(실제 단기예보는 약 3일).
function shortTerm(q) {
  const base = parseKst(q.base_date, q.base_time);
  const t = baseTemp(q.nx, q.ny);
  const items = [];
  for (let i = 1; i <= 72; i++) {
    const at = new Date(base.getTime() + i * 3600e3);
    const values = {
      TMP: String(t + (at.getUTCHours() >= 12 && at.getUTCHours() <= 16 ? 5 : 0)), REH: '55', WSD: '1.5',
      POP: i % 24 < 3 ? '60' : '10', PTY: '0', PCP: '강수없음', SNO: '적설없음', SKY: '1',
    };
    for (const [category, fcstValue] of Object.entries(values)) {
      items.push({ baseDate: q.base_date, baseTime: q.base_time, category, fcstDate: ymd(at), fcstTime: hm(at), fcstValue, nx: +q.nx, ny: +q.ny });
    }
  }
  return kma(items);
}

function midLand(q) {
  const item = { regId: q.regId };
  for (let n = 3; n <= 10; n++) {
    if (n <= 7) Object.assign(item, { [`wf${n}Am`]: '맑음', [`wf${n}Pm`]: '구름많음', [`rnSt${n}Am`]: 10, [`rnSt${n}Pm`]: 20 });
    else Object.assign(item, { [`wf${n}`]: '맑음', [`rnSt${n}`]: 10 });
  }
  return kma([item]);
}

function midTemperature(q) {
  const item = { regId: q.regId };
  for (let n = 3; n <= 10; n++) Object.assign(item, { [`taMin${n}`]: 3, [`taMax${n}`]: 14 });
  return kma([item]);
}

function uv(q) {
  const item = { code: 'A07_2', areaNo: q.areaNo, date: q.time };
  for (let h = 0; h <= 75; h += 3) item[`h${h}`] = String(h % 24 < 9 ? 1 : 4);
  return kma([item]);
}

// 남한을 0.2° 간격으로 덮는 가짜 측정소(약 900곳). 어느 동네든 가까운 측정소가 있다.
const STATIONS = [];
for (let lat = 33.2; lat <= 38.6; lat += 0.2) {
  for (let lon = 124.8; lon <= 131.0; lon += 0.2) {
    STATIONS.push({ name: `모의${lat.toFixed(1)}_${lon.toFixed(1)}`, lat: lat.toFixed(4), lon: lon.toFixed(4) });
  }
}

function stationList() {
  return air(STATIONS.map((s) => ({
    stationName: s.name, addr: `모의 주소 ${s.name}`, year: '2015', mangName: '도시대기',
    item: 'SO2, CO, O3, NO2, PM10, PM2.5', dmX: s.lon, dmY: s.lat,
  })));
}

function realtime() {
  const d = nowKst();
  const dataTime = `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())} ${pad(d.getUTCHours())}:00`;
  return air(STATIONS.map((s) => ({
    stationName: s.name, sidoName: '모의', mangName: '도시대기', dataTime, pm10Value: '30', pm25Value: '15',
    pm10Grade: '1', pm25Grade: '1', o3Grade: '1', pm10Grade1h: '1', pm25Grade1h: '1', khaiValue: '-', khaiGrade: '',
    pm10Flag: '', pm25Flag: '',
  })));
}

const ROUTES = {
  '/kma/VilageFcstInfoService_2.0/getUltraSrtNcst': observation,
  '/kma/VilageFcstInfoService_2.0/getVilageFcst': shortTerm,
  '/kma/MidFcstInfoService/getMidLandFcst': midLand,
  '/kma/MidFcstInfoService/getMidTa': midTemperature,
  '/kma/LivingWthrIdxServiceV4/getUVIdxV4': uv,
  '/air/MsrstnInfoInqireSvc/getMsrstnList': stationList,
  '/air/ArpltnInforInqireSvc/getCtprvnRltmMesureDnsty': realtime,
};

// 자체 확인: 서버를 띄우기 전에 응답 모양을 점검한다.
const sample = shortTerm({ base_date: '20261111', base_time: '2300', nx: '60', ny: '127' }).response.body;
assert.strictEqual(sample.totalCount, 72 * 8, 'shortTerm 칸 수');
assert.strictEqual(`${sample.items.item[0].fcstDate} ${sample.items.item[0].fcstTime}`, '20261112 0000', 'shortTerm 날짜 넘김');
assert.ok(STATIONS.length > 800 && STATIONS.length <= 1000, '측정소는 한 쪽(1000건) 안');

http.createServer((req, res) => {
  const url = new URL(req.url, 'http://mock');
  const route = ROUTES[url.pathname];
  if (url.pathname === '/counts') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    return res.end(JSON.stringify(counts));
  }
  if (!route) {
    res.writeHead(404);
    return res.end('not mocked');
  }
  counts[url.pathname] = (counts[url.pathname] || 0) + 1;
  const body = JSON.stringify(route(Object.fromEntries(url.searchParams)));
  setTimeout(() => {
    res.writeHead(200, { 'Content-Type': 'application/json;charset=UTF-8' });
    res.end(body);
  }, DELAY_MS);
}).listen(PORT, () => console.log(`모의 날씨 서버 :${PORT} (지연 ${DELAY_MS}ms). 호출 수: GET /counts`));
