# 아키텍처 검사 위치

Gradle 프로젝트는 생성되어 있으며, 아직 ArchUnit 의존성과 실행 가능한 경계 테스트는 없다. 이번에는 테스트 위치만 마련했다.

후속 검사 대상은 modules 사이의 api 외 접근, 모듈 순환, presentation/application/domain/infrastructure의 방향, 공개 계약과 HTTP DTO의 엔티티 노출, shared/platform/bootstrap 경계다. JPA 모델은 해당 모듈 domain에 둔다. 허용/위반 예제로 규칙의 효력을 검증한다.
