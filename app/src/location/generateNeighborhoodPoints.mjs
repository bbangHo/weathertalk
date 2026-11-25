// 동네 대표점 데이터(neighborhoodPoints.json) 생성기. 서버 동네 기준 데이터(기상청 행정동 CSV)와 같은 버전을 쓴다.
// 실행: node app/src/location/generateNeighborhoodPoints.mjs   (저장소 루트에서, 서버 CSV가 바뀌면 다시 실행)
// 형식: { version, sido: [이름], sigungu: [[시도 번호, 이름]], rows: [[코드, 시군구 번호, 읍면동, 위도×1e5, 경도×1e5]] }
// 좌표는 소수 5자리(약 1m)로 줄이고, 시도·시군구 이름은 번호로 바꿔 크기를 줄인다. rows는 코드 순(거리가 같으면 코드가 작은 쪽 — 서버 규칙).
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const VERSION = '2607';
const csvPath = resolve(here, `../../../backend/src/main/resources/neighborhood/kma/kma-administrative-dong-${VERSION}.csv`);

const [header, ...lines] = readFileSync(csvPath, 'utf8').trim().split(/\r?\n/);
const col = Object.fromEntries(header.split(',').map((name, i) => [name, i]));

const sido = [];
const sigungu = [];
const sigunguIndex = new Map();
const rows = lines
  .map((line) => line.split(','))
  .sort((a, b) => a[col.code].localeCompare(b[col.code]))
  .map((f) => {
    const sidoName = f[col.sido_name];
    if (!sido.includes(sidoName)) sido.push(sidoName);
    const key = `${sidoName}|${f[col.sigungu_name]}`;
    if (!sigunguIndex.has(key)) {
      sigunguIndex.set(key, sigungu.length);
      sigungu.push([sido.indexOf(sidoName), f[col.sigungu_name]]);
    }
    const lat = Math.round(Number(f[col.latitude]) * 1e5);
    const lon = Math.round(Number(f[col.longitude]) * 1e5);
    if (!Number.isFinite(lat) || !Number.isFinite(lon)) throw new Error(`대표점 없음: ${f[col.code]}`);
    return [f[col.code], sigunguIndex.get(key), f[col.eupmyeondong_name], lat, lon];
  });

writeFileSync(resolve(here, 'neighborhoodPoints.json'), JSON.stringify({ version: VERSION, sido, sigungu, rows }) + '\n');
console.log(`neighborhoodPoints.json: ${rows.length}개 동네`);
