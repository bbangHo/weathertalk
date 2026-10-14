// 오류 분기 신호 (owner-todos §6, 07 §2-8·12, §3). UI 계층(A1~)이 구독해 가입 시트·재동의 시트·배너를 띄운다.
import { ApiError, ErrorCodes } from './errors';

export type ApiEventMap = {
  /** 비회원이 회원 전용 기능을 요청했다 → 가입 유도(SCR-10) */
  signupRequired: ApiError;
  /** 필수 약관 재동의 전 쓰기 → 재동의 시트 */
  termsReagreementRequired: ApiError;
  /** 쓰기 직전 현재 동네 확정 실패 → 재판정 안내 */
  currentNeighborhoodUnverified: ApiError;
  /** 토큰 재발급 실패로 비회원 전환됨 → "다시 로그인이 필요해요" 배너 */
  sessionExpired: ApiError;
};

export type ApiEventType = keyof ApiEventMap;
type Handler<T extends ApiEventType> = (payload: ApiEventMap[T]) => void;

export function createApiEvents() {
  const handlers = new Map<ApiEventType, Set<Handler<ApiEventType>>>();

  return {
    on<T extends ApiEventType>(type: T, handler: Handler<T>): () => void {
      const set = handlers.get(type) ?? new Set();
      set.add(handler as Handler<ApiEventType>);
      handlers.set(type, set);
      return () => {
        set.delete(handler as Handler<ApiEventType>);
      };
    },
    emit<T extends ApiEventType>(type: T, payload: ApiEventMap[T]): void {
      handlers.get(type)?.forEach((handler) => {
        try {
          handler(payload);
        } catch {
          // 구독자 오류가 요청 처리에 번지지 않게 한다(07 §5 부가 작업 분리).
        }
      });
    },
  };
}

export type ApiEvents = ReturnType<typeof createApiEvents>;

const CODE_TO_EVENT: Partial<Record<string, ApiEventType>> = {
  [ErrorCodes.SIGNUP_REQUIRED]: 'signupRequired',
  [ErrorCodes.TERMS_REAGREEMENT_REQUIRED]: 'termsReagreementRequired',
  [ErrorCodes.CURRENT_NEIGHBORHOOD_UNVERIFIED]: 'currentNeighborhoodUnverified',
};

/** 오류 코드에 맞는 신호를 보낸다. AUTHENTICATION_FAILED·REQUEST_IN_PROGRESS는 클라이언트가 직접 처리한다. */
export function dispatchApiError(events: ApiEvents, error: ApiError): void {
  const type = CODE_TO_EVENT[error.code];
  if (type) {
    events.emit(type, error);
  }
}
