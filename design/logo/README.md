# 날씨톡톡 로고 — 말줄임 구름

**생각:** 해 앞의 구름이 말풍선이 되고, 안에서 '…'(입력 중 점)이 깜빡인다. "지금 우리 동네 누군가 날씨 얘기를 하고 있다."

## 폴더

| 폴더 | 내용 |
|---|---|
| `source/` | SVG 원본(모든 변형의 기준). `build.py`로 다시 만든다 |
| `export/png/` | 큰 PNG(투명 바탕): 심벌·앱 아이콘·가로형·세로형·한 색·반전 |
| `export/web/` | 웹 파비콘 세트: `favicon.ico`(16·32·48), `favicon.svg`, 16/32/48 PNG, `apple-touch-icon.png`(180), `icon-192/512.png` |
| `export/app-ready/` | `app/assets`와 같은 이름·크기의 교체용 파일(아직 앱에 넣지 않음) |
| `export/toss-logo-600.png` | 앱인토스 콘솔용 로고 600×600 |
| `tests/` | 16px·한 색·반전·배경 테스트 시트 |
| `concepts/`, `concepts*.png` | 지난 시안(참고용) |

## 원본 SVG

| 파일 | 쓰는 곳 |
|---|---|
| `app-icon.svg` | 기본 앱 아이콘(둥근 파란 타일). 화면 안 로고, 문서, 발표 |
| `app-icon-square.svg` | 모서리 없는 정사각. 스토어·토스 콘솔처럼 플랫폼이 모서리를 깎는 곳 |
| `app-icon-small.svg` | 32px 이하(파비콘). 점 세 개를 빼고 조금 키움 |
| `symbol.svg` | 흰·밝은 바탕 위 심벌만(파란 구름 + 노란 해) |
| `symbol-reversed.svg` | 파랑·어두운 바탕 위(흰 구름 + 노란 해) |
| `symbol-black.svg`, `symbol-white.svg`, `symbol-mono-blue.svg` | 한 색(인쇄·도장·스티커·워터마크) |
| `lockup-horizontal.svg` | 가로형(아이콘 + 날씨톡톡). 헤더·배너 |
| `lockup-horizontal-reversed.svg` | 가로형, 어두운 바탕용(글자 흰색) |
| `lockup-horizontal-black.svg` | 가로형 한 색 |
| `lockup-stacked.svg` | 세로형. 스플래시·정사각 공간 |
| `wordmark.svg` | 글자만 |
| `android-foreground/monochrome/background.svg` | 안드로이드 적응형 아이콘 3장 |
| `app-icon-alt-teal.svg` | 대안 색 제안(청록 #12B5A6). 쓰지 않는다면 무시 |

## 색

| 이름 | HEX | 쓰임 |
|---|---|---|
| 톡톡 파랑 | `#2F6FED` | 타일 바탕, 흰 바탕 위 구름. 앱 `colors.primary`와 같음 |
| 햇살 노랑 | `#FFC531` | 해. 넓은 면적·글자색으로는 쓰지 않는다 |
| 먹색 | `#191F28` | 이름 글자. 앱 `colors.textPrimary`와 같음 |
| 흰색 | `#FFFFFF` | 파란 바탕 위 구름 |

## 글자

이름은 Noto Sans KR ExtraBold(800)를 윤곽선으로 바꿔 넣었다. SIL Open Font License라 로고에 써도 된다. SVG 안에 글꼴 의존성이 없다.

## 쓰는 규칙

- **여백:** 로고 둘레에 점 하나 지름의 3배(아이콘 폭의 약 20%)만큼은 비워 둔다.
- **최소 크기:** 앱 아이콘 24px, 가로형 높이 24px. 그보다 작으면 `app-icon-small`을 쓴다.
- **바탕:** 흰색·연회색(#F2F4F6)에는 `app-icon`이나 `symbol`. 파랑·어두운 바탕에는 `symbol-reversed`나 `symbol-white`.
- **하지 말 것:** 비율 늘이기·눌러 펴기, 해·구름 색 바꾸기, 그림자·그라데이션 더하기, 점 개수 바꾸기, 다른 글꼴로 이름 다시 쓰기.

## 테스트 결과

`tests/` 폴더와 아래 표를 본다.

| 테스트 | 결과 |
|---|---|
| 16px | `app-icon-small`은 파란 타일·흰 구름·노란 해로 알아볼 수 있다. 점 세 개 판은 16px에서 뭉개져서, 32px 이하는 small 판을 쓴다 |
| 한 색(검정·흰색·파랑) | 해와 구름 사이에 틈을 깎아 둬서 한 색에서도 둘이 갈라져 보인다. 점 세 개는 진짜 구멍이라 어떤 바탕에서도 비친다 |
| 반전(어두운 바탕) | 흰 구름 + 노란 해로 잘 보인다 |
| 주의 | 파란 바탕에 파란 `symbol`을 놓으면 구름이 사라진다. 이때는 `symbol-reversed`를 쓴다. 타일 아이콘을 한 색으로 바꾸면 검은 네모가 되니, 한 색이 필요하면 `symbol-black/white`를 쓴다 |

## 남은 일

- 상표 확인은 하지 않았다. 출시 전 키프리스(KIPRIS)에서 '날씨톡톡'과 비슷한 그림 상표를 찾아보는 걸 권한다.
- `app/assets` 교체는 요청할 때만 한다. 교체용 파일은 `export/app-ready/`에 있다.
- 토스 콘솔 로고 규격(투명 바탕 여부)은 콘솔 화면에서 다시 확인한다. 꽉 찬 파란 정사각 PNG로 만들어 두었다.
