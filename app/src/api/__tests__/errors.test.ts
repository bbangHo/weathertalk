import { problem, setupClient, jsonResponse } from '@/test-utils/apiClient';

import { ApiError, ErrorCodes, NetworkError, UNKNOWN_ERROR_CODE, parseErrorBody } from '../errors';

describe('parseErrorBody', () => {
  it('Problem Detail 본문에서 code·status·detail·추가 속성을 읽는다', () => {
    const error = parseErrorBody(
      422,
      JSON.stringify({
        type: 'about:blank',
        title: 'Unprocessable Content',
        status: 422,
        detail: '보는 동네를 정할 수 없습니다.',
        instance: '/api/v1/home',
        code: 'VIEWING_NEIGHBORHOOD_UNRESOLVED',
        reason: 'NOT_SPECIFIED',
      }),
    );

    expect(error).toBeInstanceOf(ApiError);
    expect(error.code).toBe('VIEWING_NEIGHBORHOOD_UNRESOLVED');
    expect(error.status).toBe(422);
    expect(error.detail).toBe('보는 동네를 정할 수 없습니다.');
    expect(error.reason).toBe('NOT_SPECIFIED');
    expect(error.properties).toEqual({ reason: 'NOT_SPECIFIED' });
  });

  it('JSON이 아니거나 code가 없으면 UNKNOWN 코드에 HTTP 상태를 보존한다', () => {
    const html = parseErrorBody(502, '<html>Bad Gateway</html>');
    expect(html.code).toBe(UNKNOWN_ERROR_CODE);
    expect(html.status).toBe(502);

    const noCode = parseErrorBody(500, JSON.stringify({ status: 500, detail: '오류' }));
    expect(noCode.code).toBe(UNKNOWN_ERROR_CODE);
    expect(noCode.detail).toBe('오류');

    expect(parseErrorBody(500, '').code).toBe(UNKNOWN_ERROR_CODE);
  });
});

describe('클라이언트 오류 구분과 분기 신호', () => {
  it('응답을 받지 못하면 NetworkError로 구분한다', async () => {
    const { client } = setupClient({
      handler: () => {
        throw new TypeError('Network request failed');
      },
    });
    await expect(client.get('/api/v1/terms')).rejects.toBeInstanceOf(NetworkError);
  });

  it.each([
    ['SIGNUP_REQUIRED', 401, 'signupRequired'],
    ['TERMS_REAGREEMENT_REQUIRED', 403, 'termsReagreementRequired'],
    ['CURRENT_NEIGHBORHOOD_UNVERIFIED', 422, 'currentNeighborhoodUnverified'],
  ] as const)('%s → %s 신호를 보내고 ApiError를 던진다', async (code, status, eventType) => {
    const { client, events } = setupClient({ handler: () => problem(status, code) });
    const received: ApiError[] = [];
    events.on(eventType, (error) => received.push(error));

    await expect(client.get('/api/v1/x')).rejects.toMatchObject({ code, status });
    expect(received).toHaveLength(1);
    expect(received[0].code).toBe(code);
  });

  it('분기 대상이 아닌 오류는 신호 없이 던진다', async () => {
    const { client, events } = setupClient({ handler: () => problem(404, ErrorCodes.NOT_FOUND) });
    const handler = jest.fn();
    events.on('signupRequired', handler);
    events.on('termsReagreementRequired', handler);
    events.on('currentNeighborhoodUnverified', handler);

    await expect(client.get('/api/v1/x')).rejects.toMatchObject({ code: 'NOT_FOUND' });
    expect(handler).not.toHaveBeenCalled();
  });

  it('구독을 해제하면 더 이상 받지 않는다', async () => {
    const { client, events } = setupClient({ handler: () => problem(401, ErrorCodes.SIGNUP_REQUIRED) });
    const handler = jest.fn();
    const off = events.on('signupRequired', handler);
    off();
    await expect(client.get('/api/v1/x')).rejects.toBeInstanceOf(ApiError);
    expect(handler).not.toHaveBeenCalled();
  });

  it('성공 응답은 JSON으로, 204는 undefined로 돌려준다', async () => {
    const { client } = setupClient({
      handler: (call) => (call.method === 'DELETE' ? jsonResponse(204) : jsonResponse(200, { ok: true })),
    });
    await expect(client.get('/api/v1/x', { query: { a: 'b c', skip: undefined, list: ['1', '2'] } })).resolves.toEqual({
      ok: true,
    });
    await expect(client.delete('/api/v1/x')).resolves.toBeUndefined();
  });

  it('쿼리 문자열을 만들고 기기 헤더를 붙인다', async () => {
    const { client, calls } = setupClient({
      handler: () => jsonResponse(200, {}),
      device: { deviceId: 'device-1', deviceKey: 'key-1' },
    });
    await client.get('/api/v1/home', { query: { neighborhoodId: '1111051500', skip: null }, device: true });

    expect(calls[0].url).toBe('http://api.test/api/v1/home?neighborhoodId=1111051500');
    expect(calls[0].headers['X-Device-Id']).toBe('device-1');
    expect(calls[0].headers['X-Device-Key']).toBe('key-1');
  });
});
