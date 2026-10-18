# 백엔드 프로젝트 초기 커밋 준비

## 배경과 결정

연결된 `OpenStudyService/OpenStudy-Backend` 저장소의 초기 커밋 이후 준비한 Spring Boot 프로젝트와 설계 자료를 정리한다. frontend와 동일하게 `main` 기준의 `chore/initialize-project-structure` 브랜치를 사용하고, 커밋 메시지는 `chore: initialize project structure`로 정한다.

## 포함한 작업

- Java 21·Spring Boot 4.1.1·Gradle Wrapper 9.8.1 설정과 실행 클래스·기본 컨텍스트 테스트.
- 20개 업무 모듈과 bootstrap/shared/platform, 대응 테스트·리소스·스크립트 디렉터리 골격.
- 디렉터리 설계·설계 결정·개발 실행계획·DB v0.4 SQL과 ERD 및 기존 구조 보관본.
- 현재 구현 범위, 빌드 전제와 문서 위치를 안내하는 루트 README.

Gradle Wrapper JAR와 기존 DB 설계 ZIP은 재현과 설계 인계 자료로 포함한다. IDE 설정, Gradle 캐시·빌드 결과, 로컬 환경·키 파일, 로그·업로드·Python 캐시는 제외한다. DB 설계 문서는 실제 Flyway 마이그레이션과 구분한다.

## 검증 결과

- DB 정적 검증 스크립트 통과: 57개 테이블, FK 123개, CHECK 60개, ERD 관계와 고정 28일 계산 예시 확인. 재생성 결과는 기존 ZIP과 줄바꿈을 제외한 내용이 일치했으며 기존 CRLF 형식을 복원했다.
- `git check-ignore`로 IDE·Gradle 캐시·빌드·로그·로컬 환경 파일 제외 확인.
- 커밋 대상의 대표적인 개인 키·서비스 토큰 패턴 검색: 일치 없음.
- 현재 PATH의 Java는 17.0.6, JAVA_HOME은 JDK 17.0.2이며 확인한 설치 위치에 JDK 21이 없어 Gradle 빌드·컨텍스트 테스트는 수행하지 않았다.

## 남은 작업

JDK 21 실행 환경, MySQL 접속 및 전용 테스트 DB 구성을 준비한 뒤 빌드·기동·컨텍스트 테스트를 검증한다. 실제 업무 구현·Flyway 마이그레이션·실DB 통합 검증은 후속 범위다. 상위 워크스페이스 문서를 참조하는 링크의 대상은 별도 관리 자료이므로 이번 저장소에 이관하지 않았다. 이번 작업은 로컬 브랜치와 커밋까지이며 원격 push는 수행하지 않는다.
