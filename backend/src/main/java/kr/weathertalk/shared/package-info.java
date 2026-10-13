/**
 * 공유 커널: 모든 모듈이 쓰는 식별자 타입, 시각, 오류 규약.
 * <p>
 * 도메인 규칙을 두지 않는다. 여기에 들어오는 타입은 모듈 간 계약(docs/to-be/contracts.md)에 등장하는 것으로 한정한다.
 */
@ApplicationModule(displayName = "shared", type = ApplicationModule.Type.OPEN)
package kr.weathertalk.shared;

import org.springframework.modulith.ApplicationModule;
