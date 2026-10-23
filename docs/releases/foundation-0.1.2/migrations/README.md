# 증분 DDL 사본

정본은 `src/main/resources/db/migration/`이다. 이 폴더는 전달 패키지 생성 때 byte 단위로 복사하며 manifest에 해시를 기록한다.

- V1: 기존 초기 스키마. 적용된 파일의 checksum을 변경하지 않았다.
- V2: 기반 확장, 기존 상품/초대 검토 표식. 기존 데이터는 target=2에서 명시 매핑한다.
- V3: 사전 검사, 이메일 정규화, 필수값·제약·권한 seed와 상품 BASELINE 기록.

일반 개발·배포는 Flyway를 사용한다. 기존 V1 DB에 이 세 파일을 처음부터 수동 실행하지 않는다. 상세 절차는 [기존 데이터 적용 안내](../../../db/foundation-upgrade.md)에 있다. ZIP은 이 상대 경로가 유지되도록 docs/ 디렉터리를 함께 포함한다.
