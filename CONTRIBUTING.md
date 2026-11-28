# 작업 규칙

## 브랜치

Git Flow를 줄인 방식을 쓴다.

| 브랜치 | 용도 | 만드는 곳 | 합치는 곳 |
|---|---|---|---|
| `main` | 운영. 여기 올라온 백엔드는 미니 PC에 자동 배포된다 | — | — |
| `dev` | 다음 출시를 모으는 곳. 기능을 합쳐 시험한다 | `main` | `main` |
| `feat/<주제>` | 새 기능 | `dev` | `dev` |
| `fix/<주제>` | 출시 전 버그 수정 | `dev` | `dev` |
| `hotfix/<주제>` | 운영 긴급 수정 | `main` | `main`, 그리고 `dev` |

- `main`과 `dev`에는 직접 커밋하지 않는다. 브랜치에서 작업하고 PR(또는 머지)로 합친다.
- `dev` → `main`은 출시할 때만 합친다. 합치기 전에 `dev`에서 CI가 통과해야 한다.
- 다 쓴 `feat/*`, `fix/*` 브랜치는 합친 뒤 지운다.
- 브랜치 이름은 영어 소문자와 `-`로 쓴다. 예: `feat/interest-neighborhood-alias`, `fix/photo-upload-403`.

## 커밋 메시지

Conventional Commits 형식에 한국어 요약을 쓴다.

```
타입(범위): 무엇을 했는지 — 왜 또는 효과
```

- 타입: `feat`(기능), `fix`(버그), `refactor`(동작 그대로 구조 변경), `test`, `docs`, `chore`(빌드·설정·의존성), `ci`(워크플로).
- 범위: `app`, `backend`, 또는 모듈 이름(`member`, `community`, `weather`, `neighborhood` 등).
- 요약은 한 줄, 끝에 마침표를 찍지 않는다.
- 예: `fix(app): 사진 업로드 Content-Type 중복 제거 — 사전 서명 PUT이 403 나던 문제`

## 배포

- 백엔드: `main`에 `backend/**` 또는 `deploy/**` 변경이 올라오면 `.github/workflows/backend.yml`이 테스트 → jar 빌드 → 미니 PC 배포 → 상태 확인을 한다. 실패하면 미니 PC가 이전 jar(`app-prev.jar`)로 되돌린다.
- 앱: 자동 배포하지 않는다. 앱인토스 번들과 Google Play 번들은 손으로 올린다.
