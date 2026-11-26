// 게시글 이미지 고르기·변환·올리기 (05 FR-COM-01·BR-COM-04, §10 구현 메모 2단계 업로드·HEIC 제외, owner-todos §6)
// - 서버는 HEIC를 읽지 못해 받지 않으므로 고른 사진을 모두 JPEG로 바꿔 올린다(긴 변은 max-dimension으로 줄인다).
// - 업로드: ① 주소 발급(형식·크기) → ② 사전 서명 주소로 파일을 그대로 PUT → ③ 업로드 확인(서버가 메타데이터 제거·재인코딩)
import { File } from 'expo-file-system';
import { ImageManipulator, SaveFormat } from 'expo-image-manipulator';
import * as ImagePicker from 'expo-image-picker';

import type { ApiClient } from '@/api/client';
import { isApiError } from '@/api/errors';

import { completeImageUpload, requestImageUpload } from './api';
import { uploadHeaders } from './uploadHeaders';
import type { UploadedImage } from './types';

export const JPEG = 'image/jpeg';
const JPEG_COMPRESS = 0.85;

export type PickedImage = { uri: string; width: number; height: number };

/** 사진첩에서 남은 장수만큼 고른다. 취소면 빈 목록 */
export async function pickImages(limit: number): Promise<PickedImage[]> {
  if (limit <= 0) {
    return [];
  }
  const result = await ImagePicker.launchImageLibraryAsync({
    mediaTypes: ['images'],
    allowsMultipleSelection: limit > 1,
    selectionLimit: limit,
    quality: 1,
    // 촬영 위치 등 메타데이터를 앱에서 읽지 않는다(서버가 지우지만 앱도 쓰지 않음).
    exif: false,
  });
  if (result.canceled) {
    return [];
  }
  return result.assets.slice(0, limit).map((asset) => ({ uri: asset.uri, width: asset.width, height: asset.height }));
}

/** 어떤 형식이든(HEIC 포함) JPEG로 다시 저장한다. 긴 변이 maxDimension보다 크면 줄인다. */
export async function convertToJpeg(image: PickedImage, maxDimension: number): Promise<PickedImage> {
  const context = ImageManipulator.manipulate(image.uri);
  const longest = Math.max(image.width, image.height);
  if (longest > maxDimension) {
    context.resize(image.width >= image.height ? { width: maxDimension, height: null } : { width: null, height: maxDimension });
  }
  const rendered = await context.renderAsync();
  const saved = await rendered.saveAsync({ format: SaveFormat.JPEG, compress: JPEG_COMPRESS });
  return { uri: saved.uri, width: saved.width, height: saved.height };
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

/** 기기 사본 한 장을 올리고 미첨부 이미지를 받는다. 회원 요청이어야 한다. */
export async function uploadImage(api: ApiClient, localUri: string, maxFileSize: number): Promise<UploadedImage> {
  const file = new File(localUri);
  const size = file.size ?? 0;
  if (!file.exists || size <= 0) {
    throw new ImageUploadError('사진 파일을 찾을 수 없어요. 다시 골라 주세요.', 'MISSING');
  }
  if (size > maxFileSize) {
    throw new ImageUploadError(REJECT_TEXT.TOO_LARGE, 'TOO_LARGE');
  }
  try {
    const ticket = await requestImageUpload(api, JPEG, size);
    const result = await file.upload(ticket.uploadUrl, {
      httpMethod: 'PUT',
      // 서명에 형식이 들어가므로 서버가 준 헤더를 그대로 붙인다.
      headers: uploadHeaders(ticket.headers, JPEG),
    });
    if (result.status < 200 || result.status >= 300) {
      throw new ImageUploadError('사진을 올리지 못했어요. 다시 시도해 주세요.', 'STORAGE');
    }
    return await completeImageUpload(api, ticket.imageId);
  } catch (error) {
    throw toUploadError(error);
  }
}
