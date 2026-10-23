# 로컬 시연 데이터

명시적 local/test 실행에서 사용할 두 지점 이상 시연 데이터의 위치다. 현재 지점 시연 데이터나 시드 실행기는 없다. 기준일·KST·KRW를 재현할 수 있게 구성하며 운영 자동 마이그레이션에 포함하지 않는다. 최초 사장님은 별도의 `LocalOwnerBootstrap`이 local 프로필과 `BOOTSTRAP_OWNER_EMAIL`, `BOOTSTRAP_OWNER_PASSWORD` 설정 시에만 생성한다. 비밀번호를 시드 SQL에 넣지 않으며 BCrypt로 저장한다. 자세한 사용법은 `docs/authentication.md`를 참고한다.
