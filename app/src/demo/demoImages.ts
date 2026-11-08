// 데모 글 사진: 네트워크 없이 보이도록 SVG 그림을 data URI로 만든다(실제 사진 아님).
import type { PostImage } from '@/community/types';

const W = 1200;
const H = 900;

function svg(body: string, top: string, bottom: string): string {
  const doc =
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${W} ${H}">` +
    `<defs><linearGradient id="g" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${top}"/>` +
    `<stop offset="1" stop-color="${bottom}"/></linearGradient></defs>` +
    `<rect width="${W}" height="${H}" fill="url(#g)"/>${body}</svg>`;
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(doc)}`;
}

const cloud = (x: number, y: number, s: number, o = 0.95) =>
  `<g fill="#fff" opacity="${o}"><ellipse cx="${x}" cy="${y}" rx="${120 * s}" ry="${48 * s}"/>` +
  `<ellipse cx="${x - 60 * s}" cy="${y - 30 * s}" rx="${70 * s}" ry="${55 * s}"/>` +
  `<ellipse cx="${x + 40 * s}" cy="${y - 45 * s}" rx="${80 * s}" ry="${65 * s}"/></g>`;

const skyline = (fill: string) =>
  `<g fill="${fill}">` +
  [
    [0, 620, 140],
    [130, 540, 110],
    [230, 600, 90],
    [310, 470, 130],
    [430, 580, 100],
    [520, 500, 150],
    [660, 610, 90],
    [740, 430, 120],
    [850, 560, 110],
    [950, 490, 140],
    [1080, 590, 120],
  ]
    .map(([x, y, w]) => `<rect x="${x}" y="${y}" width="${w}" height="${H - y}"/>`)
    .join('') +
  '</g>';

const rainLines = Array.from({ length: 70 }, (_, i) => {
  const x = (i * 97) % W;
  const y = (i * 53) % H;
  return `<line x1="${x}" y1="${y}" x2="${x - 18}" y2="${y + 60}" stroke="#E8EEF5" stroke-width="3" opacity="0.6"/>`;
}).join('');

export const DEMO_SCENES = {
  sky: svg(cloud(320, 300, 1.4) + cloud(880, 220, 1) + cloud(760, 520, 0.8, 0.85), '#4A90E2', '#CDE6FF'),
  sunset: svg(
    `<circle cx="640" cy="560" r="110" fill="#FFD27A" opacity="0.9"/>${skyline('#2A2140')}`,
    '#3B2A6B',
    '#F7A072',
  ),
  park: svg(
    cloud(300, 220, 1) +
      `<ellipse cx="300" cy="900" rx="700" ry="300" fill="#7CC27C"/><ellipse cx="1000" cy="920" rx="600" ry="280" fill="#5DAE63"/>` +
      `<rect x="760" y="470" width="24" height="160" fill="#6B4F3A"/><circle cx="772" cy="430" r="90" fill="#3E8E4E"/>` +
      `<rect x="300" y="520" width="20" height="120" fill="#6B4F3A"/><circle cx="310" cy="490" r="70" fill="#4FA35E"/>`,
    '#76B7F2',
    '#E3F2FF',
  ),
  rain: svg(
    cloud(400, 200, 1.6, 0.5) + cloud(950, 260, 1.2, 0.45) + skyline('#4B5563') + rainLines,
    '#6B7785',
    '#AEB8C4',
  ),
} as const;

export type DemoScene = keyof typeof DEMO_SCENES;

export function demoImage(id: string, scene: DemoScene): PostImage {
  return { imageId: id, url: DEMO_SCENES[scene], width: W, height: H };
}
