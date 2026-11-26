import { uploadHeaders } from '../uploadHeaders';

describe('uploadHeaders', () => {
  it('서버가 준 content-type이 있으면 Content-Type을 따로 붙이지 않는다', () => {
    const headers = uploadHeaders({ 'content-type': 'image/jpeg', 'content-length': '10' }, 'image/jpeg');
    expect(Object.keys(headers).filter((name) => name.toLowerCase() === 'content-type')).toEqual(['content-type']);
  });

  it('형식이 없으면 기본값을 붙인다', () => {
    expect(uploadHeaders({}, 'image/jpeg')).toEqual({ 'Content-Type': 'image/jpeg' });
  });
});
