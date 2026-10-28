## 작업 브랜치 운영

기능 단위 작업은 `main` 브랜치에서 직접 수행하지 않고 별도의 작업 브랜치에서 진행한다.

* 새로운 기능, 버그 수정, 리팩토링 또는 의미 있는 UX 변경을 시작하기 전에 `main`을 기준으로 작업 브랜치를 생성한다.
* 작업 브랜치 이름은 `<작업유형>/<작업명>` 형식을 사용하고, 작업명을 짧은 kebab-case로 표현한다.
* 작업 유형은 신규 기능과 UX 변경에 `feature`, 버그 수정에 `fix`, 구조 개선에 `refactor`, 문서 변경에 `docs`, 빌드·설정·유지보수에 `chore`를 사용한다.
* 브랜치 이름에 Codex, Claude Code 등 작업을 수행한 에이전트의 이름을 포함하지 않는다.
* 현재 브랜치가 `main`이거나 다른 작업 단위의 브랜치라면 파일을 수정하기 전에 새 작업 브랜치로 전환한다.
* 구현, 검증 및 커밋은 해당 작업 브랜치에서 수행하며 `main`에 직접 커밋하지 않는다.
* 커밋에는 해당 작업과 관련된 변경만 포함하고, 작업 트리에 있던 다른 변경은 스테이징하거나 커밋하지 않는다.

## 원격 main 반영 및 PR 병합

* 원격 `main` 반영과 PR 병합은 사용자가 직접 수행한다. 에이전트가 대신 실행하지 않는다.
* 에이전트의 작업 범위는 작업 브랜치에서의 구현·검증·커밋, 작업 브랜치 push, PR 생성·수정 및 검토 자료 제공까지다. 원격 반영이 필요한 작업은 PR을 준비한 뒤 사용자에게 병합을 맡긴다.
* 에이전트는 PR 병합 명령이나 API, 자동 병합 활성화·예약, 원격 `main`으로의 직접 push·ref 갱신 등 어떤 방식으로도 원격 `main`을 변경하지 않는다.
* “정리해줘”, “수정해줘”, “원격 저장소에 반영해줘” 같은 일반적인 작업 요청을 `main` 반영이나 PR 병합 권한으로 해석하지 않는다. 검증 통과, 병합 가능 상태, 저장소 관리 권한 보유도 병합 권한을 뜻하지 않는다.
* 완료 보고에서는 작업 브랜치 push·PR 생성 상태와 사용자의 병합 대기 상태를 구분해 명시한다.

## 작업 및 의사결정 기록

프로젝트의 중요한 개발 과정과 의사결정을 추적할 수 있도록 개발 기록을 지속적으로 관리한다.

### 기록 위치

* 개발 기록은 `docs/development-log/`에서 관리한다.
* 기록은 날짜와 작업 단위를 기준으로 시간순으로 누적한다.
* 기존 기록을 삭제하거나 덮어쓰지 않는다.

### 기록 대상

다음과 같은 의미 있는 작업을 수행했을 때 기록한다.

* 새로운 기능 구현
* 아키텍처 또는 기술 선택
* 기존 기능의 동작 변경
* 리팩토링
* API 또는 외부 서비스 연동
* 중요한 버그 수정
* 성능 또는 UX 개선
* 구현 방향을 결정하거나 변경한 경우

단순 오타 수정, 포맷팅 등 의사결정 가치가 없는 사소한 변경은 기록하지 않아도 된다.

### 기본 기록 구조

각 작업은 가능하면 다음 구조를 따른다.

#### 1. 배경 / 문제

* 어떤 요구사항이나 문제가 있었는지
* 왜 이 작업이 필요했는지

#### 2. 검토한 선택지

* 고려한 구현 방법이나 기술
* 각 선택지의 장단점
* 검토하지 않은 대안까지 억지로 만들지는 않는다.

#### 3. 결정 및 이유

* 최종적으로 선택한 방법
* 해당 방법을 선택한 이유와 판단 기준
* 현재 프로젝트 상황이나 제약 조건이 결정에 어떤 영향을 미쳤는지

#### 4. 구현 내용

* 실제로 수행한 핵심 작업
* 중요한 구조 또는 동작의 변화
* 필요하다면 주요 변경 파일이나 컴포넌트

#### 5. 검증 결과

* 단위 테스트, UI 테스트, Lint, 빌드 결과
* 실제 기기 또는 에뮬레이터 검증 결과
* API 호출 등 외부 연동 검증 결과

검증하지 못한 항목은 성공한 것처럼 기록하지 않고 명확하게 구분한다.

#### 6. 남은 작업 / 보류 사항

* 아직 해결하지 않은 문제
* 의도적으로 이후 단계로 미룬 작업
* 추가 검증이 필요한 사항

### 기록 원칙

* 단순히 **무엇을 변경했는지**보다 **왜 그렇게 변경했는지**를 중요하게 기록한다.
* 구현 당시의 판단과 제약을 남겨 나중에 결정의 맥락을 복원할 수 있도록 한다.
* 실제로 검토하지 않은 대안을 사후에 만들어 기록하지 않는다.
* 구현 결과와 계획을 구분한다.
* 성공한 검증과 아직 수행하지 않은 검증을 명확히 구분한다.
* 추후 결정이 변경되면 이전 기록을 수정해 없애지 말고 새로운 기록에서 변경 이유를 남긴다.


## Pull Request 작성 규칙

PR 본문은 작업 로그가 아니라 리뷰어가 변경 내용을 빠르게 이해하고 검토할 수 있도록 작성한다. 정보량보다 정보의 구조와 전달력을 우선한다.

- 다음 순서로 작성한다.
  1. **요약**: 변경 목적과 결과를 1~3문장으로 설명한다.
  2. **주요 변경 사항**: 핵심 변경을 기능·동작 단위로 구분하여 작성한다.
  3. **검증 결과**: 실행한 테스트와 확인된 주요 시나리오를 정리한다.
  4. **주의사항 및 후속 작업**: 미완료 사항, 알려진 제약, 다른 PR과의 의존성을 명시한다.
  5. **관련 이슈**: 관련 Issue·PR·개발 기록을 링크한다.

- 긴 문단으로 여러 내용을 나열하지 않는다. 제목, 목록, 적절한 줄바꿈을 활용한다.
- 구현 세부사항을 무조건 나열하지 말고, 무엇이 왜 달라졌는지 중심으로 설명한다.
- 불필요한 전문용어·약어·파일명·식별자 나열을 피한다.
- 테스트 결과는 실제 확인한 사실만 작성하고, 미검증 항목과 구분한다.
- 상세 구현 과정과 테스트 로그는 개발 기록에 남기고 PR에는 핵심 결과와 링크만 제공한다.
- PR의 완료 범위와 이슈 전체의 완료 여부를 혼동하지 않는다.
- 내용이 길어지더라도 무리하게 한 문장으로 압축하지 않는다.
- 최종 작성 후 리뷰어 관점에서 불필요한 내용과 중복 표현을 제거한다.


## GitHub Issue 기반 작업 관리

### 이슈 분석 및 작업 분할

- 작업을 시작하기 전에 관련 Issue, 현재 코드, PR, 개발 기록을 확인한다.
- 이미 구현된 기능을 중복 개발하지 않는다. 이슈의 상태만으로 구현 여부를 판단하지 않는다.
- 작업은 명확한 결과와 검증 가능한 인수 조건을 가진 단위로 나눈다.
- 가능하면 하나의 리뷰 가능한 PR로 완료할 수 있도록 구성하되, 계층이나 파일 수를 기준으로 기계적으로 분할하지 않는다.
- 함께 처리되어야 하는 업무 동작은 정합성을 검증할 수 있도록 하나의 작업 범위로 묶는다.
- 미확정 정책이 구현에 영향을 준다면 먼저 결정을 요청하거나 별도 의사결정 이슈로 관리한다.

### 이슈 작성 규칙

Task Issue에는 다음 내용을 포함한다.

1. **목적**: 해결할 문제와 필요한 이유
2. **작업 범위**: 구현할 내용과 제외할 내용
3. **인수 조건**: 완료 여부를 확인할 수 있는 체크리스트
4. **의존성**: 선행 작업 및 필요한 정책·계약
5. **검증 방법**: 테스트와 실제 동작 확인 방법
6. **참고 자료**: 관련 Issue, PR, 요구사항 및 설계 문서

- 인수 조건은 실제 동작이나 결과를 기준으로 작성한다.
- 체크박스 하나에 가능한 한 하나의 검증 조건을 작성한다.
- 기존 문서와 중복되는 상세 내용은 복사하지 않고 링크로 연결한다.
- 기술별 공통 검증 명령은 프로젝트 설정을 따른다.

### 작업 진행 및 완료

- 선행 Issue가 열려 있더라도 실제 착수에 필요한 조건이 준비되었는지 확인한다.
- 작업 중 발견한 새로운 문제는 현재 범위와의 관련성을 판단하고, 필요하면 별도 Issue로 분리한다.
- 기존 Issue를 재구성할 때 완료된 작업을 중복 등록하거나 이력을 삭제하지 않는다.
- 작업 완료 시 인수 조건별 검증 결과를 확인한다.
- 테스트 통과, 구현 완료, 실제 환경 검증 완료를 구분한다.
- 필수 인수 조건이 충족되지 않았다면 완료로 보고하지 않는다.
- 미완료 사항은 관련 Issue에 기록하고 필요한 후속 작업을 연결한다.
- PR 생성이나 테스트 통과만으로 상위 기능 전체가 완료되었다고 판단하지 않는다.
- PR 병합 권한과 절차는 기존 브랜치 운영 규칙을 따른다.

## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

When the user types `/graphify`, use the installed graphify skill or instructions before doing anything else.

Rules:
- When graphify-out/graph.json exists, run `graphify query "<question>"` before your first grep, search or source-file read for a codebase question, and put this rule in every subagent prompt that explores code. Read raw files only for lines the graph or an earlier read already located. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- Dirty graphify-out/ files are expected after hooks or incremental updates; dirty graph files are not a reason to skip graphify. Only skip graphify if the task is about stale or incorrect graph output, or the user explicitly says not to use it.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).
- A committed graphify-out/graph.json is rewritten whole on every build, so a large diff to it in a pull request is expected. Do not flag it as churn or suggest skipping the graph rebuild or its hooks.
