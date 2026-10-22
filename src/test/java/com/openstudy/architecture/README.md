# 아키텍처 검사 위치

기준일: 2026-10-22 KST. 현재 구조는 [디렉터리 설계](../../../../../../docs/architecture/directory-structure.md)를 따른다. 아직 ArchUnit 의존성과 실행 가능한 경계 테스트는 없으며 이 폴더는 후속 검사 위치다.

후속 검사는 다음 규칙을 대상으로 한다.

- Controller는 Service를 호출하고 Repository를 직접 참조하지 않는다.
- Entity는 Controller·Service·Repository·DTO에 의존하지 않는다. 요청·응답 DTO는 Entity를 노출하지 않는다.
- 다른 모듈은 명시적으로 공개한 service 진입점과 DTO만 참조한다. 모든 public Service를 자동으로 공개하지 않으며 허용할 클래스·메서드·DTO는 기능 구현 시 명시한다.
- 다른 모듈의 Controller·Repository·Entity 접근과 모듈 간 JPA 직접 연관관계를 금지한다.
- 모듈 순환 의존을 금지하고 common은 업무 모듈에 의존하지 않는다. 업무 코드는 config를 직접 호출하지 않는다.

허용하는 예제와 거부해야 하는 예제로 규칙의 효력을 검증한다. 현재 문서 변경으로 경계 검사가 구현되거나 통과한 것은 아니다.
