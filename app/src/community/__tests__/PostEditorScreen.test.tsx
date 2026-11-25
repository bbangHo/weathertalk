// SCR-08 작성·수정: 본문 검증(필수·1~1000자), HEIC→JPEG 변환, 업로드 순서(발급→PUT→확인)→작성, IMAGE_REJECTED 사유,
// 초안 저장·복원·닫기 확인, 비회원 가입 유도, 현재 동네 확인 불가, 수정은 위치 없이, 작성 후 상세로 이동·목록 무효화
import AsyncStorage from '@react-native-async-storage/async-storage';
import type { InfiniteData } from '@tanstack/react-query';
import { act, fireEvent, screen, waitFor } from '@testing-library/react-native';
import * as Location from 'expo-location';
import * as SecureStore from 'expo-secure-store';
import { Alert } from 'react-native';

import { signupSheet } from '@/auth/signupSheet';
import { termsStatus } from '@/auth/termsStatus';
import { viewingStore } from '@/neighborhood/viewingStore';
import { pendingActionStore, restoredActions } from '@/pending/pendingAction';
import { queryKeys } from '@/query/queryKeys';
import { sessionStore } from '@/session/sessionStore';
import { generalStore } from '@/storage';
import { jsonResponse, problem, tokens, type FakeCall } from '@/test-utils/apiClient';
import { detail, page, post } from '@/test-utils/communityFixtures';
import { installFetch, pathOf } from '@/test-utils/globalFetch';
import { homeView, neighborhoodResponse } from '@/test-utils/homeFixtures';
import { renderWithClient, testQueryClient } from '@/test-utils/render';
import { mockNavigation, mockRouter, resetRouterMock } from '@/test-utils/routerMock';

import { DRAFT_RESTORED, POST_LOCATION_UNAVAILABLE, PostEditorScreen } from '../PostEditorScreen';
import type { PostPage } from '../types';

// 팩토리 안에서는 import를 쓸 수 없다(호이스팅).
// eslint-disable-next-line @typescript-eslint/no-require-imports
jest.mock('expo-router', () => require('@/test-utils/routerMock').expoRouterMock());

const mockPicker = { launchImageLibraryAsync: jest.fn() };
jest.mock('expo-image-picker', () => ({
  launchImageLibraryAsync: (...args: unknown[]) => mockPicker.launchImageLibraryAsync(...args),
}));

const mockManipulator = {
  manipulate: jest.fn(),
  resize: jest.fn(),
  saveAsync: jest.fn(),
};
jest.mock('expo-image-manipulator', () => ({
  SaveFormat: { JPEG: 'jpeg', PNG: 'png', WEBP: 'webp' },
  ImageManipulator: {
    manipulate: (uri: string) => {
      mockManipulator.manipulate(uri);
      return {
        resize: (size: unknown) => mockManipulator.resize(size),
        renderAsync: async () => ({ saveAsync: (options: unknown) => mockManipulator.saveAsync(options) }),
      };
    },
  },
}));

const mockFile = { upload: jest.fn(), size: 1234, exists: true };
jest.mock('expo-file-system', () => ({
  File: class {
    uri: string;
    constructor(uri: string) {
      this.uri = uri;
    }
    get size() {
      return mockFile.size;
    }
    get exists() {
      return mockFile.exists;
    }
    upload(url: string, options: unknown) {
      return mockFile.upload(this.uri, url, options);
    }
  },
}));

const mockedLocation = Location as jest.Mocked<typeof Location>;
const granted = { status: 'granted', granted: true, canAskAgain: false, expires: 'never' } as never;

type AlertButton = { text?: string; onPress?: () => void | Promise<void> };
let alertSpy: jest.SpyInstance;

async function asMemberAtCurrent(currentId = 'N1') {
  await sessionStore.saveTokens(tokens('m'));
  await generalStore.set('onboardingCompleted', true);
  await generalStore.set('currentNeighborhood', { neighborhoodId: currentId, resolvedAt: new Date().toISOString() });
  await viewingStore.hydrate();
}

function pickOne(uri = 'file:///photos/IMG_0001.HEIC', width = 4032, height = 3024) {
  mockPicker.launchImageLibraryAsync.mockResolvedValueOnce({ canceled: false, assets: [{ uri, width, height }] });
}

/** 업로드·작성 서버: 이미지 발급→확인, 게시글 작성, 동네 이름 */
function server(overrides: (call: FakeCall) => Response | null = () => null) {
  return installFetch((call) => {
    const custom = overrides(call);
    if (custom) {
      return custom;
    }
    const path = pathOf(call);
    if (path.startsWith('/api/v1/neighborhoods/')) {
      return jsonResponse(200, neighborhoodResponse('N1', '역삼1동'));
    }
    if (path === '/api/v1/community/images') {
      return jsonResponse(200, {
        imageId: 'img1',
        uploadUrl: 'https://s3.test/uploads/img1?sig=1',
        headers: { 'Content-Type': 'image/jpeg' },
        expiresAt: '2026-10-15T01:00:00Z',
      });
    }
    if (path === '/api/v1/community/images/img1/complete') {
      return jsonResponse(200, { imageId: 'img1', url: 'https://s3.test/images/img1.jpg', width: 2048, height: 1536, contentType: 'image/jpeg' });
    }
    if (path === '/api/v1/community/posts' && call.method === 'POST') {
      return jsonResponse(200, { postId: 'new1' });
    }
    return problem(500, 'X');
  });
}

const apiPaths = (calls: FakeCall[]) => calls.map((call) => `${call.method} ${pathOf(call)}`).filter((p) => !p.includes('/neighborhoods/'));

beforeEach(async () => {
  await AsyncStorage.clear();
  (SecureStore as unknown as { __store: Map<string, string> }).__store.clear();
  sessionStore.becomeGuest();
  termsStatus.reset();
  restoredActions.reset();
  signupSheet.setMounted(false);
  viewingStore.reset();
  resetRouterMock();
  jest.clearAllMocks();
  mockFile.size = 1234;
  mockFile.exists = true;
  mockFile.upload.mockResolvedValue({ status: 200, headers: {}, body: '' });
  mockManipulator.saveAsync.mockResolvedValue({ uri: 'file:///cache/converted.jpg', width: 2048, height: 1536 });
  mockedLocation.getForegroundPermissionsAsync.mockResolvedValue(granted);
  alertSpy = jest.spyOn(Alert, 'alert').mockImplementation(() => undefined);
});

afterEach(() => alertSpy.mockRestore());

describe('본문 검증', () => {
  it('비어 있거나 공백뿐이면 올릴 수 없고, 1000자까지 남은 글자 수를 보여주며 넘으면 막는다', async () => {
    server();
    renderWithClient(<PostEditorScreen />);
    const submit = () => screen.getByTestId('editor-submit').props.accessibilityState.disabled;

    expect(submit()).toBe(true);
    expect(screen.getByTestId('editor-remaining').props.children).toBe('1000자 남음');
    fireEvent.changeText(screen.getByTestId('editor-body'), '   \n ');
    expect(submit()).toBe(true);

    fireEvent.changeText(screen.getByTestId('editor-body'), '가');
    expect(submit()).toBe(false);
    expect(screen.getByTestId('editor-remaining').props.children).toBe('999자 남음');

    fireEvent.changeText(screen.getByTestId('editor-body'), '가'.repeat(1000));
    expect(submit()).toBe(false);
    expect(screen.getByTestId('editor-remaining').props.children).toBe('0자 남음');

    fireEvent.changeText(screen.getByTestId('editor-body'), '😀'.repeat(1001));
    expect(submit()).toBe(true);
    expect(screen.getByTestId('editor-remaining').props.children).toBe('-1자 남음');
  });
});

describe('새 글 작성', () => {
  it('고른 사진(HEIC 포함)을 긴 변 2048의 JPEG로 바꾼다', async () => {
    server();
    renderWithClient(<PostEditorScreen />);
    pickOne();

    fireEvent.press(screen.getByTestId('editor-add-image'));
    await screen.findByTestId('editor-image-0');

    expect(mockPicker.launchImageLibraryAsync).toHaveBeenCalledWith(
      expect.objectContaining({ mediaTypes: ['images'], selectionLimit: 4 }),
    );
    expect(mockManipulator.manipulate).toHaveBeenCalledWith('file:///photos/IMG_0001.HEIC');
    expect(mockManipulator.resize).toHaveBeenCalledWith({ width: 2048, height: null });
    expect(mockManipulator.saveAsync).toHaveBeenCalledWith({ format: 'jpeg', compress: 0.85 });
  });

  it('발급 → 사전 서명 PUT → 확인 순서로 올린 뒤 현재 동네 ID·요청 식별자와 함께 작성하고, 상세로 바꿔 열며 목록을 무효화한다', async () => {
    await asMemberAtCurrent();
    const { calls } = server();
    const client = testQueryClient();
    client.setQueryData<InfiniteData<PostPage>>(queryKeys.community.feed('N1'), { pages: [page([])], pageParams: [null] });
    client.setQueryData(queryKeys.home.byNeighborhood('N1'), homeView());
    renderWithClient(<PostEditorScreen feelingRecordId="rec-1" />, client);
    await waitFor(() => expect(screen.getByTestId('editor-neighborhood').props.children).toContain('역삼1동'));

    pickOne();
    fireEvent.press(screen.getByTestId('editor-add-image'));
    await screen.findByTestId('editor-image-0');
    fireEvent.changeText(screen.getByTestId('editor-body'), ' 비 와요 ');
    fireEvent.press(screen.getByTestId('editor-submit'));

    await waitFor(() => expect(mockRouter.replace).toHaveBeenCalledWith('/community/new1'));
    expect(apiPaths(calls)).toEqual([
      'POST /api/v1/community/images',
      'POST /api/v1/community/images/img1/complete',
      'POST /api/v1/community/posts',
    ]);
    expect(calls.find((call) => pathOf(call) === '/api/v1/community/images')!.body).toEqual({
      contentType: 'image/jpeg',
      contentLength: 1234,
    });
    expect(mockFile.upload).toHaveBeenCalledWith('file:///cache/converted.jpg', 'https://s3.test/uploads/img1?sig=1', {
      httpMethod: 'PUT',
      headers: { 'Content-Type': 'image/jpeg' },
    });
    const created = calls.find((call) => pathOf(call) === '/api/v1/community/posts')!;
    expect(created.body).toEqual({
      body: '비 와요',
      imageIds: ['img1'],
      feelingRecordId: 'rec-1',
      neighborhoodId: 'N1',
    });
    expect(created.headers['Idempotency-Key']).toBeTruthy();
    expect(await generalStore.get('postDraft')).toBeNull();
    expect(client.getQueryState(queryKeys.community.feed('N1'))?.isInvalidated).toBe(true);
    expect(client.getQueryState(queryKeys.home.byNeighborhood('N1'))?.isInvalidated).toBe(true);
    // 성공 후 이동은 닫기 확인을 띄우지 않는다
    expect(mockNavigation.emitBeforeRemove()).toBe(false);
  });

  it('서버가 사진을 거부하면(IMAGE_REJECTED) 그 사진에 사유를 붙이고 글은 만들지 않으며 초안을 남긴다', async () => {
    await asMemberAtCurrent();
    const { calls } = server((call) =>
      pathOf(call) === '/api/v1/community/images/img1/complete'
        ? problem(422, 'IMAGE_REJECTED', { detail: '이미지로 읽을 수 없어요', reason: 'UNREADABLE' })
        : null,
    );
    renderWithClient(<PostEditorScreen />);
    pickOne();
    fireEvent.press(screen.getByTestId('editor-add-image'));
    await screen.findByTestId('editor-image-0');
    fireEvent.changeText(screen.getByTestId('editor-body'), '사진 글');
    fireEvent.press(screen.getByTestId('editor-submit'));

    expect(await screen.findByTestId('editor-image-error-0')).toHaveTextContent('이미지로 읽을 수 없어요');
    expect(screen.getByTestId('editor-error')).toBeTruthy();
    expect(calls.some((call) => pathOf(call) === '/api/v1/community/posts')).toBe(false);
    expect(await generalStore.get('postDraft')).toMatchObject({
      body: '사진 글',
      images: [{ localUri: 'file:///cache/converted.jpg', imageId: null }],
    });
  });

  it('현재 동네를 확인할 수 없으면 막고 아무것도 올리지 않는다', async () => {
    await asMemberAtCurrent();
    mockedLocation.getForegroundPermissionsAsync.mockResolvedValue({ ...(granted as object), status: 'denied', granted: false } as never);
    const { calls } = server();
    renderWithClient(<PostEditorScreen />);
    fireEvent.changeText(screen.getByTestId('editor-body'), '글');
    fireEvent.press(screen.getByTestId('editor-submit'));

    expect(await screen.findByText(POST_LOCATION_UNAVAILABLE)).toBeTruthy();
    expect(apiPaths(calls)).toEqual([]);
  });

  it('비회원이 올리면 초안을 남기고 보류 행동과 함께 가입 시트를 연다', async () => {
    const { calls } = server();
    renderWithClient(<PostEditorScreen feelingRecordId="rec-1" />);
    fireEvent.changeText(screen.getByTestId('editor-body'), '비회원 글');
    fireEvent.press(screen.getByTestId('editor-submit'));

    await waitFor(() => expect(mockRouter.push).toHaveBeenCalledWith('/signup'));
    expect(await pendingActionStore.load()).toEqual({
      kind: 'post',
      route: '/post-editor',
      neighborhoodId: null,
      payload: { feelingRecordId: 'rec-1' },
    });
    expect(await generalStore.get('postDraft')).toMatchObject({ body: '비회원 글', images: [], feelingRecordId: 'rec-1' });
    expect(apiPaths(calls)).toEqual([]);
    expect(screen.getByTestId('editor-body').props.value).toBe('비회원 글');
  });
});

describe('초안', () => {
  it('남겨 둔 초안을 불러온다', async () => {
    server();
    await generalStore.set('postDraft', {
      body: '쓰던 글',
      images: [{ localUri: 'file:///cache/a.jpg', imageId: null }],
      feelingRecordId: null,
      savedAt: '2026-10-15T00:00:00Z',
    });
    renderWithClient(<PostEditorScreen />);

    await waitFor(() => expect(screen.getByTestId('editor-body').props.value).toBe('쓰던 글'));
    expect(screen.getByText(DRAFT_RESTORED)).toBeTruthy();
    expect(screen.getByTestId('editor-image-0')).toBeTruthy();
  });

  it('쓰던 중 닫으려 하면 "초안 남기기"를 묻고, 고르면 초안을 저장한 뒤 닫는다', async () => {
    server();
    renderWithClient(<PostEditorScreen />);
    // 빈 화면은 묻지 않고 닫는다
    expect(mockNavigation.emitBeforeRemove()).toBe(false);

    fireEvent.changeText(screen.getByTestId('editor-body'), '남길 글');
    await waitFor(() => expect(mockNavigation.emitBeforeRemove()).toBe(true));
    const buttons = alertSpy.mock.calls[alertSpy.mock.calls.length - 1][2] as AlertButton[];
    expect(buttons.map((button) => button.text)).toEqual(['계속 쓰기', '버리기', '초안 남기기']);
    await act(async () => {
      buttons.find((button) => button.text === '초안 남기기')!.onPress!();
    });
    await waitFor(() => expect(mockNavigation.dispatch).toHaveBeenCalled());
    expect(await generalStore.get('postDraft')).toMatchObject({ body: '남길 글' });
  });
});

describe('수정', () => {
  it('원래 동네 그대로 위치 없이 최종 상태(본문·이미지·첨부 해제)를 보낸다', async () => {
    await sessionStore.saveTokens(tokens('m'));
    const original = post('p1', {
      mine: true,
      body: '원래 글',
      images: [{ imageId: 'old1', url: 'https://s3.test/old1.jpg', width: 10, height: 10 }],
      attachedFeelingRecord: {
        recordId: 'rec-1',
        temperature: 'COLD',
        humidity: null,
        wind: null,
        recordedAt: '2026-10-15T00:00:00Z',
        temperatureSensitivity: 'NORMAL',
      },
    });
    const { calls } = installFetch((call) =>
      call.method === 'PUT'
        ? jsonResponse(200, { ...original, body: '고친 글' })
        : pathOf(call) === '/api/v1/community/posts/p1'
          ? jsonResponse(200, detail(original))
          : problem(500, 'X'),
    );
    renderWithClient(<PostEditorScreen postId="p1" />);

    await waitFor(() => expect(screen.getByTestId('editor-body').props.value).toBe('원래 글'));
    expect(screen.getByTestId('editor-neighborhood').props.children).toContain('바꿀 수 없어요');
    fireEvent.changeText(screen.getByTestId('editor-body'), '고친 글');
    fireEvent.press(screen.getByTestId('editor-detach'));
    fireEvent.press(screen.getByTestId('editor-submit'));

    await waitFor(() => expect(mockRouter.back).toHaveBeenCalled());
    const put = calls.find((call) => call.method === 'PUT')!;
    expect(pathOf(put)).toBe('/api/v1/community/posts/p1');
    expect(put.body).toEqual({ body: '고친 글', imageIds: ['old1'], detachFeelingRecord: true });
    expect(mockedLocation.getForegroundPermissionsAsync).not.toHaveBeenCalled();
    expect(mockedLocation.getCurrentPositionAsync).not.toHaveBeenCalled();
    expect(await generalStore.get('postDraft')).toBeNull();
  });
});
