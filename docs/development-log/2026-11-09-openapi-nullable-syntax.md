# 2026-11-09 OpenAPI 계약의 3.1 nullable 문법을 3.0.3 형식으로 수정

## 1. 배경 / 문제

- `docs/design/foundation/openapi.json`은 `openapi: 3.0.3` 문서이고, 계약 PR마다 swagger-cli validate로 검증해 왔다.
- M10 AI 채팅 계약(#92, `0.1.12-m10-ai-chat`) 이후 main이 검증에 실패한다.
  - 오류: `#/components/schemas/AiExecution must match exactly one schema in oneOf`
- 원인은 `Ai*` 스키마에 들어간 OpenAPI 3.1 문법이다. 3.0.3에서는 둘 다 허용되지 않는다.
  - 타입 배열 `"type": ["string", "null"]` 27곳
  - `oneOf: [{$ref}, {"type": "null"}]` 5곳
- M12 계약 작업(#106, PR #192)에서 발견했고, 그 PR 범위 밖이라 별도 작업으로 분리했다.

## 2. 검토한 선택지

- **문서를 `openapi: 3.1.0`으로 올리기**: 다른 마일스톤 스키마 187곳이 3.0 `nullable`을 쓰고 있어 문서 전체 의미가 바뀐다. Frontend 생성기 영향도 확인해야 해서 범위를 넘는다.
- **3.1 문법 32곳을 3.0.3 `nullable` 형식으로 바꾸기(선택)**: 기존 문서 관례와 같고, 어떤 필드가 null 가능한지는 그대로다.

## 3. 결정 및 이유

- 기존 관례를 그대로 따른다.
  - 타입 배열 → 단일 `type` + `"nullable": true`
  - `{"type": "null"}`을 포함한 `oneOf` → 남은 `$ref` 하나를 감싼 `allOf` + `"nullable": true`
- 의미 변화가 없는 순수 문법 수정이라 계약 버전(`0.1.13-m12-workforce-requests`)과 설명은 바꾸지 않았다.

## 4. 구현 내용

- `openapi.json`의 32곳을 줄 단위 스크립트로 바꿨다. 2칸 들여쓰기, LF, BOM 없는 UTF-8, 끝 줄바꿈을 유지하고 바꾼 부분 외에는 diff가 없다.
- `AiContractTests`에 `nullableFieldsUseOpenApi30Syntax`를 추가했다.
  - 문서 전체에 타입 배열이나 `"type": "null"`이 다시 들어오면 실패한다.
  - `AiExecution`의 `pocCode`·`resultCategory`·`querySpec`이 여전히 nullable이고 `querySpec`이 `AiQuerySpec`을 가리키는지 확인한다.
- 기존 아키텍처 테스트 중 3.1 문법을 단언하던 것은 없었다.

## 5. 검증 결과

- `npx --no-install @apidevtools/swagger-cli validate docs/design/foundation/openapi.json` → valid (수정 전에는 위 오류로 실패)
- 의미 동일성: 수정 전 문서를 파싱해 3.1 형식을 위 규칙으로 정규화한 결과가 수정 후 문서와 정확히 같음을 Python으로 확인했다.
- `./gradlew test --tests 'com.openstudy.architecture.*'` → 91개 통과(새 테스트 포함)
- `git diff --check` 통과

## 6. 남은 작업 / 보류 사항

- Frontend 타입 생성 결과에 차이가 있는지는 확인하지 않았다. 의미는 같으므로 차이가 없을 것으로 예상하지만 미검증이다.
