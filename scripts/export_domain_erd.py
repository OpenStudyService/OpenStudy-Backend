"""Split docs/db/erd.mmd into readable per-domain ERDs for docs/final/01-erd-overview.md.

Usage: python scripts/export_domain_erd.py
Reads only docs/db/erd.mmd (regenerate it first with scripts/export_schema_snapshot.py).
Writes the generated block between the GENERATED markers in docs/final/01-erd-overview.md and
the standalone Mermaid files in docs/final/erd/.
Every table must belong to exactly one domain; the script fails when a new table is not classified.
"""
import re
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ERD = ROOT / 'docs/db/erd.mmd'
DOC = ROOT / 'docs/final/01-erd-overview.md'
OUT_DIR = ROOT / 'docs/final/erd'

DOMAINS = [
    ('access', '계정·권한·보안', 'Access & Security', [
        'employees', 'branch_memberships', 'permission_definitions', 'permission_presets', 'permission_preset_grants',
        'membership_permission_grants', 'employee_invitations', 'invitation_permission_grants', 'invitation_send_jobs',
        'password_reset_tokens', 'administration_guard', 'audit_events', 'idempotency_records', 'workspace_event_outbox',
        'retention_rules', 'retention_runs', 'SPRING_SESSION', 'SPRING_SESSION_ATTRIBUTES']),
    ('branch', '지점 운영·시설', 'Branch Operations', [
        'branches', 'branch_weekly_hours', 'branch_closures', 'announcements', 'announcement_branches',
        'facility_types', 'facilities', 'facility_works']),
    ('customer', '고객·문의', 'Customers', [
        'customers', 'customer_status_events', 'customer_service_interactions', 'customer_merges',
        'inquiries', 'inquiry_events']),
    ('commerce', '상품·결제·이용권·정산', 'Commerce', [
        'product_categories', 'products', 'product_branches', 'product_revisions', 'payments', 'refunds',
        'passes', 'pass_balance_events', 'daily_closings', 'daily_closing_adjustments']),
    ('space', '공간: 좌석·스터디룸·사물함', 'Space', [
        'seats', 'seat_sessions', 'seat_session_moves', 'seat_session_corrections', 'rooms', 'room_weekly_hours',
        'room_reservations', 'room_reservation_slots', 'room_usages', 'lockers', 'locker_assignments',
        'locker_assignment_charges']),
    ('workforce', '인력: 근무·근태·휴가', 'Workforce', [
        'shifts', 'shift_events', 'shift_swaps', 'attendance_records', 'attendance_breaks', 'attendance_corrections',
        'leave_grants', 'leave_requests', 'leave_allocations', 'leave_balance_events']),
    ('collab', '협업: 채팅·업무', 'Collaboration', [
        'conversations', 'conversation_members', 'conversation_thread_reads', 'messages', 'work_tasks',
        'task_checklist_items', 'task_events']),
    ('ai', 'AI 분석(DomainLens)', 'AI Analytics', [
        'ai_threads', 'ai_requests', 'ai_conversations', 'ai_messages', 'ai_executions', 'ai_work_records',
        'ai_traces', 'ai_embedding_cache', 'ai_plan_cache']),
]
# Actor columns (who created/approved/processed) point at employees from almost every table. They are
# omitted outside the access domain so each diagram shows the business relationships.
ACTOR_PARENT = 'employees'


def parse():
    text = ERD.read_text(encoding='utf-8')
    tables, cur = {}, None
    rels = []
    for line in text.splitlines():
        if m := re.match(r'^  (\w+) \{$', line):
            cur = m.group(1)
            tables[cur] = []
        elif line.strip() == '}':
            cur = None
        elif cur:
            parts = line.split()
            tables[cur].append((parts[0], parts[1], parts[2] if len(parts) > 2 else ''))
        elif m := re.match(r'^  (\w+) (\|\||\|o)--o\{ (\w+) : "([^:]+): ([^"]+)"$', line):
            rels.append({'parent': m.group(1), 'card': m.group(2), 'child': m.group(3), 'name': m.group(4),
                         'cols': m.group(5).split(',')})
    return tables, rels


def show_column(typ, name, flags):
    return bool(flags) or typ.startswith('enum') or name in {'version', 'amount_krw', 'status'}


def mermaid(code, tables, rels, domain_of):
    members = [t for d in DOMAINS if d[0] == code for t in d[3]]
    keep = [r for r in rels if r['child'] in members
            and (code == 'access' or r['parent'] != ACTOR_PARENT or r['child'] == 'branch_memberships')]
    stubs = sorted({r['parent'] for r in keep if r['parent'] not in members}, key=lambda s: s.encode())
    fk_used = {(r['child'], c) for r in keep for c in r['cols']}
    lines = ['erDiagram']
    for t in members:
        lines.append(f'  {t} {{')
        for typ, name, flags in tables[t]:
            shown = flags.replace('FK', '').strip(',') if 'FK' in flags and (t, name) not in fk_used else flags
            if show_column(typ, name, shown):
                short = 'enum' if typ.startswith('enum') else typ
                lines.append(f'    {short} {name}' + (f' {shown}' if shown else ''))
        lines.append('  }')
    for t in stubs:
        pk = [(typ, n) for typ, n, f in tables[t] if 'PK' in f]
        lines.append(f'  {t} {{')
        lines += [f'    {typ} {n} PK' for typ, n in pk]
        lines.append(f'    string domain "{domain_of[t]}"')
        lines.append('  }')
    for r in keep:
        lines.append(f"  {r['parent']} {r['card']}--o{{ {r['child']} : \"{','.join(r['cols'])}\"")
    return '\n'.join(lines) + '\n', len(keep), len(stubs)


def overview(rels, domain_of):
    titles = {d[0]: d[1] for d in DOMAINS}
    edges = Counter()
    for r in rels:
        a, b = domain_of[r['parent']], domain_of[r['child']]
        if a != b and not (r['parent'] == ACTOR_PARENT and b != 'access'):
            edges[(a, b)] += 1
    lines = ['flowchart LR']
    for code, title, _, members in DOMAINS:
        lines.append(f'  {code}["{title}<br/>{len(members)}개 테이블"]')
    for (a, b), n in sorted(edges.items()):
        lines.append(f'  {a} -->|FK {n}| {b}')
    return '\n'.join(lines) + '\n'


def main():
    tables, rels = parse()
    domain_of = {t: d[0] for d in DOMAINS for t in d[3]}
    dupes = [t for t, n in Counter(t for d in DOMAINS for t in d[3]).items() if n > 1]
    missing = sorted(set(tables) - set(domain_of))
    unknown = sorted(set(domain_of) - set(tables))
    if dupes or missing or unknown:
        raise SystemExit(f'domain map out of date: duplicated={dupes} unclassified={missing} unknown={unknown}')
    titles = {d[0]: d[1] for d in DOMAINS}
    domain_label = {t: titles[c] for t, c in domain_of.items()}
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    over = overview(rels, domain_of)
    (OUT_DIR / '00-domain-map.mmd').write_text(over, encoding='utf-8', newline='\n')
    out = ['<!-- GENERATED by scripts/export_domain_erd.py from docs/db/erd.mmd. Do not edit by hand. -->', '',
           '### 영역 지도', '',
           '화살표는 부모 영역 → 자식 영역이고, 숫자는 영역을 넘는 물리 FK 수다. 직원(`employees`)을 가리키는 작성자·승인자 FK는 거의 모든 테이블에 있어 제외했다.', '',
           '```mermaid', over.rstrip(), '```', '']
    for i, (code, title, eng, members) in enumerate(DOMAINS, 1):
        body, nrel, nstub = mermaid(code, tables, rels, domain_label)
        (OUT_DIR / f'{i:02d}-{code}.mmd').write_text(body, encoding='utf-8', newline='\n')
        out += [f'### {i}. {title}', '',
                f'테이블 {len(members)}개 · 표시한 관계 {nrel}개 · 다른 영역 참조 {nstub}개(`domain` 속성이 있는 상자는 다른 영역의 테이블). '
                f'원본: [`erd/{i:02d}-{code}.mmd`](erd/{i:02d}-{code}.mmd)', '',
                '```mermaid', body.rstrip(), '```', '']
    text = DOC.read_text(encoding='utf-8')
    start, end = '<!-- BEGIN GENERATED -->', '<!-- END GENERATED -->'
    head, rest = text.split(start, 1)
    _, tail = rest.split(end, 1)
    DOC.write_text(head + start + '\n' + '\n'.join(out) + end + tail, encoding='utf-8', newline='\n')
    print({'tables': len(tables), 'relationships': len(rels), 'domains': {d[0]: len(d[3]) for d in DOMAINS}})


if __name__ == '__main__':
    main()
