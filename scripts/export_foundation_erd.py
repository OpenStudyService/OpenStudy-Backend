"""Render verified MySQL metadata as editable Mermaid and a printable core ERD.
Usage: python scripts/export_foundation_erd.py docs/releases/foundation-0.1.2
Requires reportlab. Does not read any application data or credentials.
"""
import json
import re
import sys
from collections import defaultdict
from pathlib import Path
from reportlab.pdfgen import canvas
from reportlab.lib.colors import HexColor, white
from reportlab.lib.pagesizes import A3, landscape
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont

out = Path(sys.argv[1]).resolve()
meta = json.loads((out / 'schema-metadata.json').read_text(encoding='utf-8'))
font_path = Path(sys.argv[2]) if len(sys.argv)>2 else Path('C:/Windows/Fonts/malgun.ttf')
pdfmetrics.registerFont(TTFont('Body',str(font_path)))
columns = defaultdict(list)
for col in meta['columns']: columns[col['TABLE_NAME']].append(col)
keys = meta['foreignKeys']
core = ['employees','branches','branch_weekly_hours','branch_memberships','permission_definitions',
        'permission_presets','permission_preset_grants','membership_permission_grants',
        'employee_invitations','invitation_permission_grants','invitation_send_jobs',
        'product_categories','products','product_branches','product_revisions','audit_events',
        'administration_guard','idempotency_records','workspace_event_outbox','SPRING_SESSION','SPRING_SESSION_ATTRIBUTES']
assert len(core)==21 and set(core)<=set(columns)

def mermaid(names):
    result=['erDiagram']
    for name in names:
        result.append(f'  {name} {{')
        for c in columns[name]:
            typ=re.sub(r'[^a-zA-Z0-9_]','_',c['COLUMN_TYPE'])
            flags=[]
            if c['COLUMN_KEY']=='PRI': flags.append('PK')
            if any(k['TABLE_NAME']==name and k['COLUMN_NAME']==c['COLUMN_NAME'] for k in keys): flags.append('FK')
            if c['COLUMN_KEY']=='UNI': flags.append('UK')
            note='nullable' if c['IS_NULLABLE']=='YES' else 'not null'
            result.append(f'    {typ} {c["COLUMN_NAME"]}'+(' '+','.join(flags) if flags else '')+f' "{note}"')
        result.append('  }')
    grouped=defaultdict(list)
    for k in keys:
        if k['TABLE_NAME'] in names and k['REFERENCED_TABLE_NAME'] in names:
            grouped[(k['TABLE_NAME'],k['CONSTRAINT_NAME'],k['REFERENCED_TABLE_NAME'],k['DELETE_RULE'])].append(k)
    for (child,_,parent,rule),kk in grouped.items():
        nullable=any(next(c for c in columns[child] if c['COLUMN_NAME']==k['COLUMN_NAME'])['IS_NULLABLE']=='YES' for k in kk)
        # ||/o| is FK nullability only. Parent-to-child maxima are deliberately not inferred from partial unique metadata.
        result.append(f'  {parent} '+('o|' if nullable else '||')+'--o{ '+child+' : "'+','.join(k['COLUMN_NAME'] for k in kk)+' / '+rule+'"')
    return '\n'.join(result)+'\n'

(out/'erd-foundation.mmd').write_text(mermaid(core),encoding='utf-8')
(out/'erd-all-tables.mmd').write_text(mermaid([t for t in meta['tables'] if t!='flyway_schema_history']),encoding='utf-8')
W,H=landscape(A3)
pdf=canvas.Canvas(str(out/'OpenStudy-Foundation-ERD.pdf'),pagesize=(W,H))
pdf.setTitle('OpenStudy Foundation ERD 0.1.2 - verified MySQL V3')
pdf.setAuthor('OpenStudy')
navy=HexColor('#263E61'); ink=HexColor('#18263A'); gray=HexColor('#65748B'); light=HexColor('#EEF2F7')
page=0
def text(x,y,value,size=10,color=ink):
    pdf.setFillColor(color);pdf.setFont('Body',size);pdf.drawString(x,y,str(value))
def header(title,subtitle):
    global page
    page+=1; text(36,H-42,title,20,navy);text(36,H-63,subtitle,10,gray)
    pdf.setStrokeColor(HexColor('#BAC5D3'));pdf.line(36,H-76,W-36,H-76)
    text(36,23,'OpenStudy | contract 0.1.2-design | Flyway V3 | MySQL '+meta['mysqlVersion'],9,gray)
    text(W-100,23,str(page),9,gray)
def wrap(value,width,size=9):
    lines=[]
    for line in value.split('\n'):
        buf=''
        for ch in line:
            if pdfmetrics.stringWidth(buf+ch,'Body',size)>width:
                lines.append(buf);buf=ch
            else:buf+=ch
        lines.append(buf)
    return lines
def box(name,x,top,width=345,short=False):
    rows=columns[name]
    if short:rows=[c for c in rows if c['COLUMN_KEY']=='PRI']
    height=31+len(rows)*14+10
    pdf.setFillColor(white);pdf.setStrokeColor(HexColor('#BDCAD9'));pdf.roundRect(x,top-height,width,height,5,fill=1,stroke=1)
    pdf.setFillColor(navy);pdf.rect(x,top-28,width,28,fill=1,stroke=0)
    text(x+10,top-19,name+(' (참조)' if short else ''),10,white)
    for i,c in enumerate(rows):
        fk=any(k['TABLE_NAME']==name and k['COLUMN_NAME']==c['COLUMN_NAME'] for k in keys)
        mark=('PK ' if c['COLUMN_KEY']=='PRI' else '')+('FK ' if fk else '')
        typ=c['COLUMN_TYPE'].replace(' unsigned',' U')
        if typ.startswith('enum('):typ='ENUM'
        name_text=mark+c['COLUMN_NAME']+(' ?' if c['IS_NULLABLE']=='YES' else '')
        fs=8.4
        assert pdfmetrics.stringWidth(name_text,'Body',fs)<width-105,(name,name_text)
        text(x+9,top-43-i*14,name_text,fs)
        text(x+width-100,top-43-i*14,typ,7.9,gray)
    return (x,top,width,height)
def link(a,b,label,lane=0):
    # Edges use the gutter between table boxes, never crossing text.
    ax,at,aw,ah=a;bx,bt,bw,bh=b
    pdf.setStrokeColor(HexColor('#7992AE'));pdf.setLineWidth(.8)
    if abs(ax-bx)<1:
        x=ax+aw-17-lane*10;y1=at-ah;y2=bt
        pdf.line(x,y1,x,y2)
        pdf.saveState();pdf.translate(x-5,min(y1,y2)+8);pdf.rotate(90);text(0,0,label,7,gray);pdf.restoreState()
    else:
        if ax>bx:a,b=b,a;ax,at,aw,ah=a;bx,bt,bw,bh=b
        y1=max(at-ah+15,at-40-lane*18);y2=max(bt-bh+15,bt-40-lane*18);mid=(ax+aw+bx)/2
        p=pdf.beginPath();p.moveTo(ax+aw,y1)
        if bx-(ax+aw)>100:
            # A skipped middle table must not be crossed by a relationship.
            left=ax+aw+16+lane*3;right=bx-16-lane*3;route=H-91+lane*5
            p.lineTo(left,y1);p.lineTo(left,route);p.lineTo(right,route);p.lineTo(right,y2);p.lineTo(bx,y2)
            mid=right
        else:
            p.lineTo(mid,y1);p.lineTo(mid,y2);p.lineTo(bx,y2)
        pdf.drawPath(p)
        # Label in the open gutter, rotated for narrow spacing.
        pdf.saveState();pdf.translate(mid+3,min(y1,y2)-5);pdf.rotate(90);text(0,0,label,7,gray);pdf.restoreState()
    pdf.setLineWidth(.8)
def note(y,value):
    for line in wrap(value,W-72,9):text(36,y,line,9,gray);y-=14
    return y

header('1. 기반 ERD - 읽는 방법','실제 Docker MySQL에 V1-V3 적용 후 INFORMATION_SCHEMA에서 추출한 21개 기반 테이블')
text(36,H-112,'전체 구축 범위: 업무·세션 65개 + Flyway 이력 1개',16,navy)
notes=[
 '이번 변경은 기반 21개 테이블을 다룹니다. 기존 V1의 고객·결제·좌석·예약·채널·근태 등 후속 영역은 보존되며, 그 업무 설계나 API 구현이 완료되었다는 뜻은 아닙니다.',
 '이 PDF는 기반 테이블의 모든 컬럼과 주요 관계를 영역별로 보여줍니다. 전체 65개 테이블의 상세 FK는 erd-all-tables.mmd, 정확한 CREATE TABLE·CHECK·UNIQUE·인덱스는 schema-full.sql을 기준으로 확인합니다.',
 'PK = 기본키, FK = 외래키, ? = NULL 허용, U = UNSIGNED. ENUM의 실제 값과 CHECK 식은 SQL에 있습니다. 페이지의 선은 표시된 테이블 사이 주요 업무 FK를 연결하며 생략된 추적 필드 FK는 각 테이블의 FK 표기와 원본 Mermaid에서 확인합니다.',
 'Mermaid의 부모 끝 || / o|는 FK의 NULL 허용 여부를 나타냅니다. 자식 끝 o{는 보수적 표기이며 복합 UNIQUE에 따른 실제 최대 개수는 SQL 제약으로 판단합니다.',
 'created_by·updated_by·approved_by 등 직원 참조는 FK RESTRICT입니다. 기존 행의 알 수 없는 작업자는 NULL을 유지합니다. Spring Session PRINCIPAL_NAME은 논리적 직원 ID이며 직원 FK가 없습니다.',
 'product_revisions.product_id는 삭제 시 SET NULL인 생존 연결입니다. original_product_id는 FK가 없는 영구 원본 ID입니다. 거래 이력이 있는 상품은 기존 payments/passes의 RESTRICT와 서비스 검사로 보호합니다.',
 'DB version은 0부터, API version은 DB+1입니다. 유효한 역할·지점 범위, 최소 활성 관리자, 상품 최소 1개 판매 지점, 분류 kind 일치, 유형 변경 금지는 서비스 구현에서 검증해야 합니다.',
 '이 SQL과 ERD에는 실제 직원·비밀번호·토큰·세션·구매 데이터가 없습니다. 전체 SQL에는 검증된 V1-V3 Flyway 이력과 기본 권한 사전·프리셋·가드만 포함합니다.'
]
y=H-147
for i,n in enumerate(notes,1):y=note(y,f'{i}. {n}')-14
text(36,y-6,'구성: 조직 → 권한 → 초대 → 상품 → 감사·세션·전송 기반 → 전체 테이블 색인',12,navy)
pdf.showPage()

header('2. 조직·계정·지점 소속','4개 테이블 | 승인 기록의 과거 NULL은 보존하며 역할·권한 변경은 서비스 트랜잭션으로 보호')
a=box('employees',36,H-105);b=box('branches',430,H-105);c=box('branch_memberships',824,H-105)
d=box('branch_weekly_hours',430,365)
link(a,c,'employee_id',0);link(b,c,'branch_id',1);link(b,d,'branch_id')
note(80,'공통 추적 FK의 직원 참조는 RESTRICT. 글로벌 OWNER의 최대 1명은 owner_slot UNIQUE, 최소 활성 관리자는 administration_guard 잠금 + 서비스 검증입니다.')
pdf.showPage()

header('3. 권한 사전·프리셋·실효 권한','4개 기반 테이블 + 소속 참조 | 프리셋 수정은 이미 복사한 grants를 바꾸지 않음')
a=box('permission_definitions',36,H-105);b=box('permission_presets',430,H-105)
c=box('permission_preset_grants',430,370);d=box('membership_permission_grants',824,370);e=box('branch_memberships',824,H-105,short=True)
link(a,c,'permission_id');link(b,c,'preset_id / CASCADE');link(a,d,'permission_id',1);link(e,d,'membership_id / CASCADE')
note(125,'기본 STAFF 프리셋은 grants 빈 집합, MANAGER 프리셋은 9개. people:manage는 관리자 전용이며 역할과 현재 지점 범위를 함께 확인해야 합니다.')
pdf.showPage()

header('4. 초대·승인·메일 발송','3개 기반 테이블 + 지점 참조 | 토큰 원문은 저장하지 않고 hash·revision·lease만 유지')
a=box('employee_invitations',36,H-105);b=box('invitation_send_jobs',430,H-105);c=box('invitation_permission_grants',824,H-105);d=box('branches',824,430,short=True)
link(a,b,'invitation_id');link(a,c,'invitation_id',1);link(a,d,'branch_id',2)
note(120,'초대 이름 NOT NULL, 진행 초대 UNIQUE, token_hash UNIQUE와 상태별 CHECK를 적용합니다. 권한 스냅샷은 승인 후에도 보존하며 자동 재시도는 기존 TTL을 유지합니다.')
note(98,'accepted_employee_id·created_by·approved_by·rejected_by·cancelled_by·updated_by는 직원 FK. preset_id는 프리셋 삭제 시 SET NULL입니다.')
pdf.showPage()

header('5. 상품·분류·판매 지점·변경 이력','4개 기반 테이블 + 지점 참조 | 상품 삭제 후에도 원래 ID와 snapshot으로 이력 보존')
a=box('products',36,H-105);b=box('product_categories',430,H-105);c=box('product_revisions',824,H-105)
d=box('product_branches',430,350);e=box('branches',824,350,short=True)
link(a,b,'category_id');link(a,c,'product_id / SET NULL',1);link(a,d,'product_id',2);link(d,e,'branch_id')
note(155,'기존 payments.product_id와 passes.product_id는 RESTRICT를 유지합니다. 실패·환불 결제도 거래 참조로 취급하며, 미거래 상품만 판매 지점 연결 제거 후 삭제할 수 있습니다.')
note(128,'BASELINE은 마이그레이션 당시 확인된 현재 조건만 뜻합니다. 공유 상품 가격·구성은 동일하며 지점별 가격이 다르면 별도 상품을 만듭니다.')
pdf.showPage()

header('6. 감사·멱등성·이벤트·JDBC 세션','6개 기반 테이블 | 관리자 가드는 단일 행, Spring Session은 기존 표준 스키마 유지')
box('audit_events',36,H-105);box('idempotency_records',430,H-105);box('workspace_event_outbox',824,H-105)
box('administration_guard',36,390);a=box('SPRING_SESSION',430,390);b=box('SPRING_SESSION_ATTRIBUTES',824,390);link(a,b,'SESSION_PRIMARY_ID / CASCADE')
note(95,'다중 ECS task의 SSE fan-out은 후속 배포 설계입니다. outbox 테이블만으로 여러 인스턴스에 연결된 모든 사용자에게 전달이 보장되지 않습니다.')
pdf.showPage()

header('7. 전체 테이블 색인','65개 업무·세션 테이블 | 기반은 CORE, 기존 후속 영역은 V1로 표시. 상세 전체 ERD는 Mermaid 원본 참조')
names=[t for t in meta['tables'] if t!='flyway_schema_history']
for i,name in enumerate(names):
    column=i//23; row=i%23;x=36+column*390;y=H-112-row*25
    text(x,y,f'{i+1:02d}  {name}',9.5)
    text(x+310,y,'CORE' if name in core else 'V1',9,navy if name in core else gray)
pdf.showPage();pdf.save()
print(json.dumps({'pages':page,'coreTables':len(core),'allBusinessTables':len(names),'foreignKeyColumns':len(keys)},ensure_ascii=False))
