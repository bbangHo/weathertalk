# 날씨톡톡 (리부트)

동네 주민이 직접 느낀 날씨를 온도 민감도별로 모아, 수치 예보보다 현실적인 동네 날씨를 보여주는 커뮤니티 앱.

## 구조

| 경로 | 내용 |
|---|---|
| [`backend/`](backend/) | Spring Boot 4 + Spring Modulith 모듈러 모놀리스 (Java 21, PostgreSQL+PostGIS, Redis) |
| [`app/`](app/) | React Native + Expo (TypeScript) |
| [`CONTEXT.md`](CONTEXT.md) | 용어집 — 코드·문서·대화의 도메인 용어 기준 |
| [`docs/to-be/`](docs/to-be/README.md) | 리부트 스펙, 모듈 간 계약([contracts.md](docs/to-be/contracts.md)), 레거시 이슈 처리표 |
| [`docs/adr/`](docs/adr/) | 설계 결정 기록 |
| [`docs/plan/`](docs/plan/implementation-plan.md) | 구현 계획·마일스톤 |
| [`docs/spec/`](docs/spec/README.md) | 레거시 현행 스펙(참고용) |

## 백엔드 실행

Docker가 실행 중이어야 한다. JDK 21이 없으면 Gradle이 자동으로 내려받는다.

```bash
cd backend
./gradlew test       # 모듈 경계 검증 + Testcontainers 통합 테스트
./gradlew bootRun    # compose.yaml(PostGIS, Redis)을 자동으로 띄우고 실행
```

모듈 다이어그램은 `./gradlew test` 후 `backend/build/spring-modulith-docs/`에 생성된다.

## 앱 실행

```bash
cd app
npx expo start
```
