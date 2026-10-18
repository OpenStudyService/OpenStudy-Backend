# 아키텍처 검사 위치

BE-002 빌드 설정 후 BE-003에서 ArchUnit 테스트를 추가한다. 현재 실행 가능한 테스트는 없다.

검사 대상은 modules 사이의 api 외 접근, 모듈 순환, presentation/application/domain/infrastructure의 방향, api와 HTTP DTO에 엔티티 노출, shared/platform/bootstrap 경계다. JPA 모델은 모듈 domain에 허용한다. 모듈 간 JPA 엔티티 직접 연관도 차단한다. 허용하는 작은 예제와 거부해야 하는 예제로 규칙의 효력을 확인한다.
