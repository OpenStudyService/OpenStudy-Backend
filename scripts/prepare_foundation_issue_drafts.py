"""Prepare local GitHub registration bodies; never calls GitHub or assigns work."""
import hashlib
import json
import re
from pathlib import Path

root=Path(__file__).resolve().parents[1]
design=root/'docs/design/foundation'
out=design/'github-drafts'
out.mkdir(parents=True,exist_ok=True)
plan=(design/'implementation-plan.md').read_text(encoding='utf-8')
api_hash=hashlib.sha256((design/'openapi.json').read_bytes()).hexdigest()
repo={'backend':'OpenStudyService/OpenStudy-Backend','frontend':'OpenStudyService/OpenStudy-Frontend'}
milestones=[
 {'id':'M1','title':'1차 기반 인증·조직 관리','description':'사장님 초기화·로그인·지점·직원 권한·회수 흐름. B11의 권한 회수/최소 갱신 부분도 이 단계에서 검증한다.'},
 {'id':'M2','title':'1차 직원 초대·승인','description':'메일 초대·신규/기존 본인 수락·승인 대기·승인/반려·허용 지점 진입.'},
 {'id':'M3','title':'1차 상품·실시간 갱신','description':'상품 유형·판매 지점·변경/삭제 이력·판매 상태·갱신·전체 통합 시나리오.'},
]
depends={
 'B01':[], 'B02':['B01'], 'B03':['B01','B02'], 'B04':['B03'], 'B05':['B01','B02','B03','B04'],
 'B06':['B03','B04','B05'], 'B07':['B02','B03','B04','B05','B06'], 'B08':['B07'],
 'B09':['B04','B06','B07','B08'], 'B10':['B02','B03','B04','B05','B06'], 'B11':['B03'],
 'F01':['B01'], 'F02':['F01','B05','B06'], 'F03':['F01','B07','B08','B09'], 'F04':['F01','B10'],
 'V01':[f'B{i:02}' for i in range(1,12)]+[f'F{i:02}' for i in range(1,5)],
}
issues=[]
for match in re.finditer(r'^### ([BFV]\d{2})\. ([^\n]+)\n(.*?)(?=^### |\Z)',plan,flags=re.M|re.S):
    iid,title,body=match.groups()
    body=re.split(r'^## ',body,flags=re.M)[0].strip()
    target='frontend' if iid.startswith('F') else 'backend'
    milestone='M1' if iid in ['B01','B02','B03','B04','B05','B06','F01','F02'] else 'M2' if iid in ['B07','B08','B09','F03'] else 'M3'
    status='implemented-in-delivery-branch' if iid=='B02' else 'not-started'
    text=f'# {iid}. {title}\n\n'
    text+=f'대상: `{repo[target]}` · 마일스톤: `{milestone}` · 로컬 초안 ID: `{iid}` (GitHub 번호 아님)\n\n'
    text+='기준: foundation `0.1.2-design` / DB `V3` / SRS 8개 중분류·61개 요구사항.\n\n'
    text+=f'OpenAPI SHA-256: `{api_hash}`. 등록 시 전달 패키지 PR/병합 커밋과 선행 실제 이슈·PR 링크를 연결한다.\n\n'
    text+='선행 로컬 ID: '+(', '.join(depends[iid]) or '없음')+'. 프론트 mock 계약 작업은 선행 API 계약이 고정되면 시작할 수 있으나 live 연동 종료에는 백엔드 구현이 필요하다.\n\n'
    if iid=='B02':text+='**구현·검증 완료된 전달 작업이다. 새 구현을 지시하지 않는다. 패키지 PR 병합 후 완료 증거를 연결해 종료하거나 등록에서 제외한다.**\n\n'
    if iid=='B11':text+='**M1 선행 게이트:** 현재 DB 인가·회수 후 세션/캐시/스트림 차단과 최소 갱신은 B03/B04/B06/F01/F02와 함께 M1에서 검증한다. M3까지 미루지 않는다. M3에서는 상품까지 갱신 범위를 통합한다.\n\n'
    text+='## 범위와 인수 조건\n\n'+body+'\n\n## 공통 완료 증거\n\n'
    text+='- [ ] 이슈 범위와 확정 정책을 반영하고 관련 요구사항 ID를 PR에 연결했다.\n'
    text+='- [ ] 정상·빈 데이터·권한 거부·충돌 등 해당 인수 시나리오를 검증했다.\n'
    text+='- [ ] 실제 실행 명령·결과와 미검증/후속 항목을 구분했다.\n'
    text+='- [ ] AGENTS.md에 따라 작업 브랜치·관련 변경만 커밋·개발 로그를 적용했다.\n'
    text+='- [ ] 대응 저장소 계약/PR을 연결하고 리뷰 후 통합할 수 있다.\n\n'
    text+='정본: backend `docs/design/foundation/`의 openapi.json, database.md, decisions.md, traceability.md. 실행 DDL은 Flyway migration. 엑셀은 읽기용 생성 사본이다.\n'
    file=f'{iid}.md';(out/file).write_text(text,encoding='utf-8',newline='\n')
    issues.append({'id':iid,'title':f'{iid}. {title}','repository':repo[target],'milestone':milestone,'dependsOn':depends[iid],'bodyFile':file,'status':status,'registrationDisposition':'record-completed-or-skip' if iid=='B02' else 'create-after-review'})
assert len(issues)==16 and set(depends)=={i['id'] for i in issues}
manifest={'version':'0.1.2-design','githubRegistrationPerformed':False,'contractSha256':api_hash,
          'repositories':repo,'milestones':[{**m,'repositories':list(repo.values())} for m in milestones],
          'releaseGates':{'M1':'B11 권한 회수·최소 갱신 부분 검증 필요','M3':'V01 실제 통합 검증 성공'},'issues':issues}
(out/'registration.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
index='# GitHub 등록 준비 파일\n\n아직 원격 마일스톤·이슈를 등록하지 않았다. 이 파일들은 실행 예약이나 자동 배정이 아니다.\n\n'
index+='논리 마일스톤은 3개이며 GitHub milestone은 저장소별 자원이므로 frontend/backend 각 저장소에 같은 3개를 만든다(원격 자원 6개). 담당자와 기한은 지정하지 않았다. 기존 동명 항목을 먼저 확인하고 중복 등록하지 않는다.\n\n'
index+='[registration.json](registration.json)에 대상 저장소·선행 관계·마일스톤·본문 파일이 있다. 등록 전 패키지 PR/기준 커밋과 실제 선행 링크를 연결한다. B02는 이번 전달 브랜치에서 완료했으므로 재개발하지 않는다.\n\n'
index+='| ID | 저장소 | 단계 | 상태 | 본문 |\n| --- | --- | --- | --- | --- |\n'
for i in issues:index+=f'| {i["id"]} | {"frontend" if i["id"].startswith("F") else "backend"} | {i["milestone"]} | {"전달 브랜치 구현 완료" if i["id"]=="B02" else "미착수"} | [{i["title"]}]({i["bodyFile"]}) |\n'
index+='\n등록 후에는 실제 이슈 번호/URL을 추적표에 연결한다. 로컬 ID B01 등의 문자열을 GitHub `#번호`로 해석하지 않는다. [작업 운영 방식](../agent-workflow.md)에 따라 주 담당 한 명이 계약·migration·통합 순서를 조정한다.\n'
(out/'README.md').write_text(index,encoding='utf-8',newline='\n')
print(json.dumps({'milestoneGroups':3,'milestoneResources':6,'issueDrafts':16,'implementedIssues':['B02'],'githubRegistrationPerformed':False}))
