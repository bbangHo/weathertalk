// 작성 초안 (07 §6.1 작성 초안, SCR-08 닫기·업로드 중 이탈·가입 유도 때 보존). 새 글만 초안을 둔다(수정은 원래 글이 있다).
import { generalStore, type StoredDraftImage, type StoredPostDraft } from '@/storage';

export type PostDraft = Omit<StoredPostDraft, 'savedAt'>;

export const postDraftStore = {
  async load(): Promise<PostDraft | null> {
    const stored = await generalStore.get('postDraft');
    if (!stored || typeof stored.body !== 'string') {
      return null;
    }
    const images: StoredDraftImage[] = Array.isArray(stored.images)
      ? stored.images.filter((image) => image && (image.localUri || image.imageId))
      : [];
    return { body: stored.body, images, feelingRecordId: stored.feelingRecordId ?? null };
  },
  async save(draft: PostDraft, now: Date = new Date()): Promise<void> {
    await generalStore.set('postDraft', { ...draft, savedAt: now.toISOString() });
  },
  async clear(): Promise<void> {
    await generalStore.remove('postDraft');
  },
};

export function isDraftEmpty(draft: PostDraft): boolean {
  return draft.body.trim() === '' && draft.images.length === 0 && draft.feelingRecordId === null;
}
