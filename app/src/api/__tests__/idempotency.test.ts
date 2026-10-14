// contracts §4: REQUEST_IN_PROGRESS는 같은 요청 식별자로 잠시 후 재시도
import { jsonResponse, problem, setupClient } from '@/test-utils/apiClient';

import { ErrorCodes } from '../errors';
import { createIdempotencyKeyHolder, newIdempotencyKey, requiresIdempotencyKey } from '../idempotency';

describe('요청 식별자', () => {
  it('REQUEST_IN_PROGRESS면 같은 Idempotency-Key로 재시도해 성공을 돌려준다', async () => {
    let attempts = 0;
    const { client, calls, sleep } = setupClient({
      handler: () => {
        attempts += 1;
        return attempts < 3 ? problem(409, ErrorCodes.REQUEST_IN_PROGRESS) : jsonResponse(200, { recordId: 'r1' });
      },
    });
    const key = newIdempotencyKey();

    await expect(
      client.post('/api/v1/feeling/records', { temperature: 'COLD' }, { idempotencyKey: key }),
    ).resolves.toEqual({ recordId: 'r1' });

    expect(calls).toHaveLength(3);
    expect(calls.map((call) => call.headers['Idempotency-Key'])).toEqual([key, key, key]);
    expect(sleep).toHaveBeenCalledTimes(2);
  });

  it('재시도 횟수를 넘으면 REQUEST_IN_PROGRESS를 던진다', async () => {
    const { client, calls } = setupClient({ handler: () => problem(409, ErrorCodes.REQUEST_IN_PROGRESS) });

    await expect(
      client.post('/api/v1/weather/field-reports', {}, { idempotencyKey: 'abcdefgh-1' }),
    ).rejects.toMatchObject({ code: ErrorCodes.REQUEST_IN_PROGRESS });
    expect(calls).toHaveLength(4); // 첫 요청 + 재시도 3회
  });

  it('생성성 쓰기에 식별자가 없으면 보내지 않는다', async () => {
    const { client, calls } = setupClient({ handler: () => jsonResponse(200, {}) });
    await expect(client.post('/api/v1/community/posts', { body: '안녕' })).rejects.toThrow('idempotencyKey');
    expect(calls).toHaveLength(0);
  });

  it('식별자가 필요한 경로를 안다', () => {
    expect(requiresIdempotencyKey('POST', '/api/v1/feeling/records')).toBe(true);
    expect(requiresIdempotencyKey('POST', '/api/v1/feeling/feedbacks')).toBe(true);
    expect(requiresIdempotencyKey('POST', '/api/v1/weather/field-reports')).toBe(true);
    expect(requiresIdempotencyKey('POST', '/api/v1/community/posts')).toBe(true);
    expect(requiresIdempotencyKey('POST', '/api/v1/community/posts/p1/comments')).toBe(true);
    expect(requiresIdempotencyKey('POST', '/api/v1/community/comments/c1/replies')).toBe(true);
    expect(requiresIdempotencyKey('PUT', '/api/v1/community/posts/p1/like')).toBe(false);
    expect(requiresIdempotencyKey('POST', '/api/v1/weather/field-reports/r1/cancel')).toBe(false);
  });

  it('식별자 보관기는 renew 전까지 같은 값을 준다(같은 행동의 다시 시도)', () => {
    const holder = createIdempotencyKeyHolder();
    const first = holder.current();
    expect(holder.current()).toBe(first);
    expect(first).toMatch(/^[A-Za-z0-9_-]{8,100}$/);
    expect(holder.renew()).not.toBe(first);
  });
});
