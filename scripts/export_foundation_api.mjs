// Run from a directory resolving the bundled @oai/artifact-tool package.
// Usage: node export_foundation_api.mjs <openapi.json> <output-dir>
import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
const requireFromCwd = createRequire(path.resolve('package.json'));
const { Workbook, SpreadsheetFile } = await import(pathToFileURL(requireFromCwd.resolve('@oai/artifact-tool')).href);
const [source, out] = process.argv.slice(2);
if (!source || !out) throw new Error('Expected source OpenAPI JSON and output directory');
const raw = await fs.readFile(source, 'utf8'), spec = JSON.parse(raw);
if(raw.includes('\r')) throw new Error('OpenAPI source must use LF endings (.gitattributes) before export.');
const hash = crypto.createHash('sha256').update(raw).digest('hex');
await fs.mkdir(out, { recursive: true });
const ref = r => r.split('/').slice(1).reduce((o,k)=>o[k.replaceAll('~1','/').replaceAll('~0','~')],spec);
const resolve = s => s?.$ref ? {...ref(s.$ref), ...Object.fromEntries(Object.entries(s).filter(([k])=>k!=='$ref'))} : (s||{});
const label = s => s?.$ref?.split('/').at(-1) || s?.type || (s?.oneOf?'oneOf':s?.allOf?'allOf':'-');
const type = s => { const r=resolve(s); return r.oneOf ? r.oneOf.map(label).join(' | ') : r.allOf ? r.allOf.map(label).join(' & ') : r.type==='array' ? `array<${label(r.items)}>` : (r.type||label(s))+(r.format?` (${r.format})`:''); };
const limits = s => {s=resolve(s); const a=[]; for(const k of ['enum','minimum','maximum','minLength','maxLength','pattern','minItems','maxItems','uniqueItems','additionalProperties','default','readOnly','writeOnly']) if(s[k]!==undefined) a.push(`${k}: ${JSON.stringify(s[k])}`); return a.join('\n');};
const ops=[];
for(const [url,p] of Object.entries(spec.paths)) for(const [method,o] of Object.entries(p)) if(['get','post','put','patch','delete','head','options'].includes(method)) ops.push({...o,url,method:method.toUpperCase(),parameters:[...(p.parameters||[]),...(o.parameters||[])]});
if(ops.length!==40 || Object.keys(spec.components.schemas).length!==57) throw new Error('Unexpected contract size; review release expectations');
const wb=Workbook.create(), counts={};
function col(n){let a='';for(n++;n;n=Math.floor((n-1)/26))a=String.fromCharCode(65+(n-1)%26)+a;return a;}
function lines(s,width){return String(s??'').split('\n').reduce((n,l)=>n+Math.max(1,Math.ceil([...l].reduce((z,c)=>z+(c.charCodeAt(0)>255?12:6.5),0)/(width-16))),0);}
function sheet(name,title,headers,rows,widths){
 const sh=wb.worksheets.add(name),last=col(headers.length-1),end=rows.length+5;
 sh.showGridLines=false; sh.tabColor=name==='사용안내'?'#778899':'#263E61';
 sh.getRange(`A1:${last}${Math.max(end,6)}`).format.font={name:'Malgun Gothic',size:11,color:'#18263A'};
 sh.getRange('A2').values=[[title]];sh.getRange('A2').format.font={size:16,bold:true};sh.getRange('A2').format.rowHeight=30;
 sh.getRange(`A3:${last}3`).format.borders={preset:'doubleBottom',style:'thin',color:'#A8B5C5'};
 sh.getRange(`A5:${last}${end}`).values=[headers,...rows.map(r=>r.map(v=>v===undefined?'':v))];
 sh.getRange(`A5:${last}5`).format={fill:'#263E61',font:{name:'Malgun Gothic',size:11,bold:true,color:'#FFFFFF'},horizontalAlignment:'center',verticalAlignment:'center',rowHeight:30};
 sh.getRange(`A6:${last}${end}`).format.wrapText=true;
 sh.getRange(`A6:${last}${end}`).format.verticalAlignment='top';
 for(let i=0;i<widths.length;i++)sh.getRange(`${col(i)}1:${col(i)}${end}`).format.columnWidthPx=widths[i];
 for(let i=0;i<rows.length;i++){
  const height=Math.max(28,Math.max(...rows[i].map((v,c)=>lines(v,widths[c])))*15+10);
  sh.getRange(`A${i+6}:${last}${i+6}`).format.rowHeight=height;
  if(i%2===1)sh.getRange(`A${i+6}:${last}${i+6}`).format.fill='#F1F4F8';
 }
 if(name!=='사용안내'){const t=sh.tables.add(`A5:${last}${end}`,true,`FoundationTable${Object.keys(counts).length+1}`);t.style='TableStyleMedium2';sh.freezePanes.freezeRows(5);sh.freezePanes.freezeColumns(name==='확정정책'?1:2);}
 counts[name]=rows.length;return sh;
}
sheet('사용안내','OpenStudy | 1차 기반 API 설계',['항목','내용'],[
 ['버전',spec.info.version],['범위','검토 완료 8개 중분류 / 61개 요구사항 / 40개 API / 57개 모델'],
 ['상태','구현 전 목표 계약입니다. 기존 로그인과 일부 조회가 있어도 이 엑셀의 전체 기능이 구현되었다는 뜻은 아닙니다.'],
 ['읽는 순서','API목록 → 요청필드·응답목록 → 공통모델 → 오류·보안·업무규칙 → 확정정책'],
 ['기준 주소',(spec.servers||[]).map(s=>s.url).join(', ')+' (API목록 URL에 포함)'],
 ['필수 / NULL','필수는 필드 존재 여부이고 NULL 허용과 별개입니다. 하위 필드는 상위 객체가 제공되었을 때의 조건입니다. oneOf는 선택한 변형 내부에서만 필수입니다.'],
 ['배열 / 모델 참조','요청필드는 중첩 필드와 oneOf 변형을 펼칩니다. 응답은 상태별 모델을 참조하며, 공통모델에 57개 모델의 모든 직접 필드·참조·조합 규칙을 표시합니다.'],
 ['인증·권한','SessionCookie와 업무 세션 범위, CSRF, 지점·역할·행위 권한을 함께 확인합니다. API목록의 빈 권한 설명은 허용을 뜻하지 않습니다.'],
 ['갱신 방법','OpenAPI를 수정한 뒤 생성 스크립트를 재실행합니다. 엑셀만 수정하면 다음 생성 때 반영되지 않습니다.'],
 ['원본',`docs/design/foundation/openapi.json | ${spec.info.version} | SHA-256 ${hash}`],
 ['관련 문서','database.md · api.md · decisions.md · traceability.md · implementation-plan.md'],
 ['후속 범위','채널·근무표·구매·발급·결제 등은 이번 계약에 추가되지 않았습니다. GitHub 마일스톤·이슈는 아직 등록하지 않았습니다.']
],[180,1060]);
sheet('API목록','API 목록 | 40 operations',['operationId','분류','Method','URL','동작','세션 모드','요구사항'],ops.map(o=>[o.operationId,o.tags?.join(', '),o.method,'/api'+o.url,o.summary,(o['x-session-modes']||[]).join(', '),(o['x-requirement-ids']||[]).join(', ')]),[230,145,80,440,330,210,280]);
const req=[];
function walk(o,where,s,prefix='',required=true,variant='기본',seen=[]){
 const r=resolve(s),name=label(s);
 if(r.oneOf||r.anyOf){(r.oneOf||r.anyOf).forEach((v,i)=>walk(o,where,v,prefix,required,`${variant} / ${r.oneOf?'oneOf':'anyOf'}:${label(v)||i+1}`,seen));return;}
 if(r.allOf){r.allOf.forEach(v=>walk(o,where,v,prefix,required,variant+' / allOf',seen));return;}
 req.push([o.operationId,where,variant,prefix||'(body)',type(s),required?'필수':'선택',r.nullable?'허용':'불가',limits(s),r.description||'',r.example===undefined?'':JSON.stringify(r.example)]);
 if(s?.$ref&&seen.includes(s.$ref))return;
 const next=s?.$ref?[...seen,s.$ref]:seen;
 if(r.properties)for(const [k,v] of Object.entries(r.properties))walk(o,where,v,prefix?prefix+'.'+k:k,(r.required||[]).includes(k),variant,next);
 if(r.items && (resolve(r.items).properties||resolve(r.items).oneOf))walk(o,where,r.items,(prefix||'(body)')+'[]',true,variant,next);
}
for(const o of ops){for(const q of o.parameters.map(resolve)){walk(o,q.in,q.schema,q.name,q.required===true);req.at(-1)[8]=[q.description,req.at(-1)[8]].filter(Boolean).join('\n');}if(o.requestBody){const b=resolve(o.requestBody);for(const [media,v]of Object.entries(b.content||{}))walk(o,media,v.schema,'',b.required===true);}}
sheet('요청필드','요청 필드 | 조건부 필수는 사용안내 참조',['operationId','위치','변형','필드 경로','타입 / 형식','존재','NULL','제약','설명','예시'],req,[230,155,300,330,200,75,65,360,440,390]);
const responses=[];for(const o of ops)for(const [status,v]of Object.entries(o.responses)){const r=resolve(v);if(!r.content)responses.push([o.operationId,status,'(본문 없음)','-',r.description||'',Object.keys(r.headers||{}).join('\n')]);for(const [media,c]of Object.entries(r.content||{}))responses.push([o.operationId,status,media,label(c.schema),r.description||'',Object.keys(r.headers||{}).join('\n')]);}
sheet('응답목록','응답 | 상태 코드별 모델·콘텐츠',['operationId','HTTP','Content-Type','모델','설명','응답 헤더'],responses,[250,80,230,300,550,270]);
const models=[];for(const [name,s] of Object.entries(spec.components.schemas)){
 models.push([name,'(모델)',type(s),'해당 없음',s.nullable?'허용':'불가',limits(s),s.description||'',s.oneOf?`oneOf: ${s.oneOf.map(label).join(' | ')}${s.discriminator?' / discriminator='+s.discriminator.propertyName:''}`:s.allOf?`allOf: ${s.allOf.map(label).join(' & ')}`:s.items?label(s.items):'',s.example===undefined?'':JSON.stringify(s.example)]);
 for(const [k,v]of Object.entries(s.properties||{})){const r=resolve(v);models.push([name,k,type(v),(s.required||[]).includes(k)?'필수':'선택',r.nullable?'허용':'불가',limits(v),r.description||'',v.$ref?label(v):r.items?label(r.items):'',r.example===undefined?'':JSON.stringify(r.example)]);}
}
sheet('공통모델','모델 사전 | 57 schemas',['모델','필드','타입 / 형식','존재','NULL','제약','설명','참조 / 조합','예시'],models,[230,230,260,90,65,420,460,400,430]);
const security=[];for(const [name,s]of Object.entries(spec.components.securitySchemes||{}))security.push(['인증',name,s.type,s.in||'',s.name||'',s.description||'']);for(const [name,r0]of Object.entries(spec.components.responses||{})){const r=resolve(r0);security.push(['공통 응답',name,'','','',r.description||'']);}for(const [name,p0]of Object.entries(spec.components.parameters||{})){const p=resolve(p0);security.push(['공통 요청',name,p.in,p.required?'필수':'선택',type(p.schema),[p.description,limits(p.schema)].filter(Boolean).join('\n')]);}
sheet('오류·보안','오류·보안 | 공통 규칙',['구분','이름','유형 / 위치','필수 / 위치','필드 / 타입','설명'],security,[140,230,190,120,230,740]);
sheet('업무규칙','API별 인가·업무 규칙',['operationId','동작','권한 조건','업무 규칙','오류 코드·확장 규칙'],ops.map(o=>[o.operationId,o.summary,o['x-permission']||'',o.description||'',Object.entries(o).filter(([k])=>k.startsWith('x-')&&!['x-permission','x-session-modes','x-requirement-ids','x-implementation-status'].includes(k)).map(([k,v])=>`${k}: ${typeof v==='string'?v:JSON.stringify(v)}`).join('\n')]),[230,310,550,850,600]);
sheet('확정정책','확정 정책 | Q1-Q12',['ID','상태','확정일','결정 내용'],Object.entries(spec['x-confirmed-policies']).map(([id,v])=>[id,'사용자 확정',v.confirmedAt,v.decision]),[80,130,140,980]);
wb.recalculate();
const scan=await wb.inspect({kind:'match',searchTerm:'#REF!|#DIV/0!|#VALUE!|#NAME\\?|#NUM!|#SPILL!|#CALC!',options:{useRegex:true,maxResults:30},maxChars:2000});
await fs.writeFile(path.join(out,'workbook-checks.json'),JSON.stringify({sourceSha256:hash,counts,errorScan:scan.ndjson,operationIds:ops.map(o=>o.operationId)},null,2));
for(const [i,name]of Object.keys(counts).entries()){
 const sh=wb.worksheets.getItem(name),last=name==='사용안내'?'B':name==='공통모델'?'F':name==='업무규칙'?'D':name==='요청필드'?'H':name==='API목록'?'E':name==='응답목록'?'E':name==='오류·보안'?'F':'D';
 const png=await wb.render({sheetName:name,range:`A1:${last}${name==='사용안내'?10:9}`,scale:1.25});
 await fs.writeFile(path.join(out,`preview-${i+1}.png`),new Uint8Array(await png.arrayBuffer()));
}
const xlsx=await SpreadsheetFile.exportXlsx(wb);await xlsx.save(path.join(out,'OpenStudy-Foundation-API.xlsx'));
console.log(JSON.stringify({version:spec.info.version,counts,sourceSha256:hash}));
