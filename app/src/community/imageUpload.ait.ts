// 앱인토스(토스 미니앱) 게시글 이미지 고르기·변환·올리기. APP_TARGET=ait 빌드에서 imageUpload.ts 대신 쓰인다(같은 내보내기).
// - 사진은 토스 사진첩(Device.getAlbumItems)에서 데이터 URI로 받고, 브라우저 캔버스로 JPEG로 다시 그린다(긴 변은 max-dimension).
// - 업로드 단계는 네이티브와 같다: ① 주소 발급 → ② 사전 서명 주소로 PUT(fetch) → ③ 업로드 확인.
//   ②는 브라우저 요청이라 저장소(S3) 버킷 CORS에 미니앱 출처가 있어야 한다.
import { Device } from '@apps-in-toss/web-framework';

import type { ApiClient } from '@/api/client';
import { isApiError } from '@/api/errors';

import { completeImageUpload, requestImageUpload } from './api';
import type { UploadedImage } from './types';

export const JPEG = 'image/jpeg';
const JPEG_COMPRESS = 0.85;
/** 토스 사진첩이 돌려줄 최대 가로 폭. 서버 max-dimension보다 크게 받아 변환 단계에서 줄인다. */
const ALBUM_MAX_WIDTH = 2048;

export type PickedImage = { uri: string; width: number; height: number };

function loadImage(uri: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const image = new Image();
    image.onload = () => resolve(image);
    image.onerror = () => reject(new ImageUploadError('사진을 읽을 수 없어요', 'UNREADABLE'));
    image.src = uri;
  });
}

/** 사진첩에서 남은 장수만큼 고른다. 취소면 빈 목록 */
export async function pickImages(limit: number): Promise<PickedImage[]> {
  if (limit <= 0) {
    return [];
  }
  const items = await Device.getAlbumItems({ types: ['PHOTO'], maxCount: limit, maxWidth: ALBUM_MAX_WIDTH, base64: true });
  const picked: PickedImage[] = [];
  for (const item of items.slice(0, limit)) {
    const image = await loadImage(item.dataUri);
    picked.push({ uri: item.dataUri, width: image.naturalWidth, height: image.naturalHeight });
  }
  return picked;
}

/** 어떤 형식이든 JPEG 데이터 URI로 다시 그린다. 긴 변이 maxDimension보다 크면 줄인다. */
export async function convertToJpeg(image: PickedImage, maxDimension: number): Promise<PickedImage> {
  const source = await loadImage(image.uri);
  const longest = Math.max(source.naturalWidth, source.naturalHeight);
  const scale = longest > maxDimension ? maxDimension / longest : 1;
  const width = Math.round(source.naturalWidth * scale);
  const height = Math.round(source.naturalHeight * scale);
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const context = canvas.getContext('2d');
  if (!context) {
    throw new ImageUploadError('사진을 읽을 수 없어요', 'UNREADABLE');
  }
  context.drawImage(source, 0, 0, width, height);
  return { uri: canvas.toDataURL(JPEG, JPEG_COMPRESS), width, height };
}

/** 업로드 실패. message는 화면에 그대로 보여줄 문구(서버 IMAGE_REJECTED 사유 포함) */
export class ImageUploadError extends Error {
  readonly reason: string | null;
  readonly cause: unknown;

  constructor(message: string, reason: string | null, cause?: unknown) {
    super(message);
    this.name = 'ImageUploadError';
    this.reason = reason;
    this.cause = cause;
  }
}

const REJECT_TEXT: Record<string, string> = {
  TYPE_NOT_ALLOWED: '올릴 수 없는 사진 형식이에요',
  TOO_LARGE: '사진이 너무 커요',
  UNREADABLE: '사진을 읽을 수 없어요',
  NOT_UPLOADED: '사진이 다 올라가지 않았어요',
};

function toUploadError(error: unknown): ImageUploadError | unknown {
  if (isApiError(error) && error.code === 'IMAGE_REJECTED') {
    return new ImageUploadError(error.detail ?? REJECT_TEXT[error.reason ?? ''] ?? '사진을 올리지 못했어요', error.reason, error);
  }
  return error;
}

/** 변환한 사진(데이터 URI) 한 장을 올리고 미첨부 이미지를 받는다. 회원 요청이어야 한다. */
export async function uploadImage(api: ApiClient, localUri: string, maxFileSize: number): Promise<UploadedImage> {
  let blob: Blob;
  try {
    blob = await (await fetch(localUri)).blob();
  } catch {
    throw new ImageUploadError('사진 파일을 찾을 수 없어요. 다시 골라 주세요.', 'MISSING');
  }
  if (blob.size <= 0) {
    throw new ImageUploadError('사진 파일을 찾을 수 없어요. 다시 골라 주세요.', 'MISSING');
  }
  if (blob.size > maxFileSize) {
    throw new ImageUploadError(REJECT_TEXT.TOO_LARGE, 'TOO_LARGE');
  }
  try {
    const ticket = await requestImageUpload(api, JPEG, blob.size);
    const response = await fetch(ticket.uploadUrl, {
      method: 'PUT',
      // 서명에 형식이 들어가므로 서버가 준 헤더를 그대로 붙인다.
      headers: { 'Content-Type': JPEG, ...ticket.headers },
      body: blob,
    });
    if (!response.ok) {
      throw new ImageUploadError('사진을 올리지 못했어요. 다시 시도해 주세요.', 'STORAGE');
    }
    return await completeImageUpload(api, ticket.imageId);
  } catch (error) {
    throw toUploadError(error);
  }
}
