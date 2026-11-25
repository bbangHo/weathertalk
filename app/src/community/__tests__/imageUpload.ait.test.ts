import { toDataUri } from '../imageUpload.ait';

jest.mock('@apps-in-toss/web-framework', () => ({ Device: {} }));

describe('toDataUri', () => {
  it('접두어 없는 base64에는 JPEG 데이터 URI 접두어를 붙인다', () => {
    expect(toDataUri('/9j/4AAQ')).toBe('data:image/jpeg;base64,/9j/4AAQ');
  });

  it('이미 주소 형태면 그대로 둔다', () => {
    for (const uri of ['data:image/png;base64,iVBOR', 'https://x/y.jpg', 'file:///a.jpg', 'blob:abc']) {
      expect(toDataUri(uri)).toBe(uri);
    }
  });
});
