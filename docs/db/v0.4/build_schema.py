from pathlib import Path
from collections import Counter,defaultdict
import re,zipfile,hashlib
base=Path(__file__).resolve().parent
D={}

def add(name, group, desc, cols, fks=(), unique=(), indexes=(), checks=()):
    assert name not in D
    D[name]=dict(group=group,desc=desc,cols=[c.strip() for c in cols.strip().split('\n') if c.strip()],fks=fks,unique=unique,indexes=indexes,checks=checks)

def fk(local,other,remote='id',ondelete='RESTRICT'):
    return (tuple(local.split(',')),other,tuple(remote.split(',')),ondelete)

# Core: single company, multiple branches; OWNER global / MANAGER branch-scoped.
add('branches','access','스터디카페 지점', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
code VARCHAR(32) NOT NULL
name VARCHAR(120) NOT NULL
address VARCHAR(300) NOT NULL
phone VARCHAR(30) NULL
status ENUM('ACTIVE','SUSPENDED') NOT NULL DEFAULT 'ACTIVE'
created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
version BIGINT UNSIGNED NOT NULL DEFAULT 0
''',unique=[('code',)],indexes=[('status',)])
add('branch_weekly_hours','access','지점 기본 영업시간. 분 단위, 종료 1440 초과는 다음날', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
branch_id BIGINT UNSIGNED NOT NULL
weekday TINYINT UNSIGNED NOT NULL
is_closed BOOLEAN NOT NULL DEFAULT FALSE
opens_minute SMALLINT UNSIGNED NULL
closes_minute SMALLINT UNSIGNED NULL
''',fks=[fk('branch_id','branches')],unique=[('branch_id','weekday')],checks=["weekday BETWEEN 1 AND 7", "((is_closed = 1 AND opens_minute IS NULL AND closes_minute IS NULL) OR (is_closed = 0 AND opens_minute < 1440 AND closes_minute > opens_minute AND closes_minute <= 2880))"])
add('employees','access','직원 로그인 계정; 이메일 ID 불변, 최초 소유자 1명', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
email VARCHAR(254) NOT NULL
password_hash VARCHAR(255) NOT NULL
name VARCHAR(100) NOT NULL
nickname VARCHAR(100) NOT NULL
avatar_url VARCHAR(500) NULL
global_role ENUM('OWNER','EMPLOYEE') NOT NULL DEFAULT 'EMPLOYEE'
owner_slot TINYINT GENERATED ALWAYS AS (CASE WHEN global_role = 'OWNER' THEN 1 ELSE NULL END) STORED
status ENUM('PENDING','ACTIVE','SUSPENDED','LEFT') NOT NULL DEFAULT 'PENDING'
theme ENUM('FOREST','NAVY','SAND') NOT NULL DEFAULT 'FOREST'
last_login_at DATETIME(6) NULL
created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
version BIGINT UNSIGNED NOT NULL DEFAULT 0
''',unique=[('email',),('owner_slot',)],indexes=[('status',)])
add('permission_definitions','access','행동 권한 코드 사전', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
code VARCHAR(80) NOT NULL
description VARCHAR(255) NOT NULL
is_admin_only BOOLEAN NOT NULL DEFAULT FALSE
''',unique=[('code',)])
add('permission_presets','access','기본 및 소유자 정의 지점 권한 프리셋', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
name VARCHAR(100) NOT NULL
role ENUM('MANAGER','STAFF') NOT NULL
is_system BOOLEAN NOT NULL DEFAULT FALSE
created_by BIGINT UNSIGNED NULL
created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
version BIGINT UNSIGNED NOT NULL DEFAULT 0
''',fks=[fk('created_by','employees','id','SET NULL')],unique=[('name','role')])
add('permission_preset_grants','access','프리셋 권한 원본 (복사시 기존 직원에 영향 없음)', '''
preset_id BIGINT UNSIGNED NOT NULL
permission_id BIGINT UNSIGNED NOT NULL
''',fks=[fk('preset_id','permission_presets','id','CASCADE'),fk('permission_id','permission_definitions')],unique=[('preset_id','permission_id')])
add('branch_memberships','access','직원-지점 배정과 관리자 최종 승인 상태', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
employee_id BIGINT UNSIGNED NOT NULL
branch_id BIGINT UNSIGNED NOT NULL
role ENUM('MANAGER','STAFF') NOT NULL DEFAULT 'STAFF'
status ENUM('PENDING','ACTIVE','REVOKED') NOT NULL DEFAULT 'PENDING'
approved_by BIGINT UNSIGNED NULL
approved_at DATETIME(6) NULL
created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
version BIGINT UNSIGNED NOT NULL DEFAULT 0
''',fks=[fk('employee_id','employees'),fk('branch_id','branches'),fk('approved_by','employees','id','SET NULL')],unique=[('employee_id','branch_id'),('branch_id','employee_id')],indexes=[('branch_id','status','role')])
add('membership_permission_grants','access','직원 지점별 실효 권한의 스냅샷', '''
membership_id BIGINT UNSIGNED NOT NULL
permission_id BIGINT UNSIGNED NOT NULL
''',fks=[fk('membership_id','branch_memberships','id','CASCADE'),fk('permission_id','permission_definitions')],unique=[('membership_id','permission_id')])
add('employee_invitations','access','초대 해시/24시간/발송 revision/최종 승인', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
branch_id BIGINT UNSIGNED NOT NULL
invitee_email VARCHAR(254) NOT NULL
initial_role ENUM('MANAGER','STAFF') NOT NULL
preset_id BIGINT UNSIGNED NULL
created_by BIGINT UNSIGNED NOT NULL
accepted_employee_id BIGINT UNSIGNED NULL
approved_by BIGINT UNSIGNED NULL
status ENUM('PENDING','ACCEPTED','APPROVED','REJECTED','CANCELLED','EXPIRED') NOT NULL DEFAULT 'PENDING'
active_invitation_slot TINYINT GENERATED ALWAYS AS (CASE WHEN status IN ('PENDING','ACCEPTED') THEN 1 ELSE NULL END) STORED
token_hash BINARY(32) NULL
revision INT UNSIGNED NOT NULL DEFAULT 0
created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
expires_at DATETIME(6) NOT NULL
accepted_at DATETIME(6) NULL
approved_at DATETIME(6) NULL
updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
version BIGINT UNSIGNED NOT NULL DEFAULT 0
''',fks=[fk('branch_id','branches'),fk('preset_id','permission_presets','id','SET NULL'),fk('created_by','employees'),fk('accepted_employee_id','employees','id','SET NULL'),fk('approved_by','employees','id','SET NULL')],unique=[('branch_id','invitee_email','active_invitation_slot')],indexes=[('invitee_email','status'),('status','expires_at')],checks=['revision >= 0'])
add('invitation_send_jobs','access','메일 발송 outbox; 토큰 원문/재생성 가능한 시크릿 저장 금지', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
invitation_id BIGINT UNSIGNED NOT NULL
revision INT UNSIGNED NOT NULL
status ENUM('PENDING','PROCESSING','SENT','FAILED','SKIPPED') NOT NULL DEFAULT 'PENDING'
attempts INT UNSIGNED NOT NULL DEFAULT 0
next_attempt_at DATETIME(6) NULL
sent_at DATETIME(6) NULL
last_error_code VARCHAR(80) NULL
created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
''',fks=[fk('invitation_id','employee_invitations')],unique=[('invitation_id','revision')],indexes=[('status','next_attempt_at')])
add('audit_events','access','핵심 변경 감사. JSON은 민감정보 제거 후 저장', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
actor_employee_id BIGINT UNSIGNED NULL
branch_id BIGINT UNSIGNED NULL
entity_type VARCHAR(80) NOT NULL
entity_id BIGINT UNSIGNED NOT NULL
action VARCHAR(80) NOT NULL
reason VARCHAR(500) NULL
before_json JSON NULL
after_json JSON NULL
occurred_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
''',fks=[fk('actor_employee_id','employees','id','SET NULL'),fk('branch_id','branches','id','SET NULL')],indexes=[('entity_type','entity_id','occurred_at'),('actor_employee_id','occurred_at'),('branch_id','occurred_at')])

# Staff workflows and team chat.
add('work_tasks','collaboration','업무와 담당자·지점, 충돌 감지 version', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
branch_id BIGINT UNSIGNED NOT NULL
title VARCHAR(200) NOT NULL
body TEXT NULL
created_by BIGINT UNSIGNED NOT NULL
assignee_employee_id BIGINT UNSIGNED NOT NULL
category VARCHAR(60) NOT NULL
priority ENUM('LOW','NORMAL','HIGH','URGENT') NOT NULL DEFAULT 'NORMAL'
status ENUM('TODO','IN_PROGRESS','DONE') NOT NULL DEFAULT 'TODO'
due_at DATETIME(6) NULL
completed_at DATETIME(6) NULL
created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
version BIGINT UNSIGNED NOT NULL DEFAULT 0
''',fks=[fk('branch_id','branches'),fk('created_by','employees'),fk('branch_id,assignee_employee_id','branch_memberships','branch_id,employee_id')],indexes=[('branch_id','status','due_at'),('assignee_employee_id','status')])
add('task_checklist_items','collaboration','업무 체크리스트', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
task_id BIGINT UNSIGNED NOT NULL
body VARCHAR(500) NOT NULL
sort_order INT UNSIGNED NOT NULL DEFAULT 0
is_done BOOLEAN NOT NULL DEFAULT FALSE
updated_by BIGINT UNSIGNED NULL
updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
''',fks=[fk('task_id','work_tasks','id','CASCADE'),fk('updated_by','employees','id','SET NULL')],indexes=[('task_id','sort_order')])
add('task_events','collaboration','업무 변경 이력', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
task_id BIGINT UNSIGNED NOT NULL
actor_employee_id BIGINT UNSIGNED NULL
event_type VARCHAR(50) NOT NULL
before_json JSON NULL
after_json JSON NULL
created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
''',fks=[fk('task_id','work_tasks'),fk('actor_employee_id','employees','id','SET NULL')],indexes=[('task_id','created_at')])
add('conversations','collaboration','전체/지점 채널 또는 1대1 DM', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
kind ENUM('GLOBAL_CHANNEL','BRANCH_CHANNEL','DM') NOT NULL
branch_id BIGINT UNSIGNED NULL
name VARCHAR(120) NULL
dm_employee_low_id BIGINT UNSIGNED NULL
dm_employee_high_id BIGINT UNSIGNED NULL
created_by BIGINT UNSIGNED NULL
created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
''',fks=[fk('branch_id','branches','id','SET NULL'),fk('dm_employee_low_id','employees'),fk('dm_employee_high_id','employees'),fk('created_by','employees','id','SET NULL')],unique=[('dm_employee_low_id','dm_employee_high_id')],indexes=[('branch_id','kind')],checks=["(kind = 'DM' AND branch_id IS NULL AND dm_employee_low_id IS NOT NULL AND dm_employee_high_id IS NOT NULL AND dm_employee_low_id < dm_employee_high_id) OR (kind = 'GLOBAL_CHANNEL' AND branch_id IS NULL AND name IS NOT NULL AND dm_employee_low_id IS NULL AND dm_employee_high_id IS NULL) OR (kind = 'BRANCH_CHANNEL' AND branch_id IS NOT NULL AND name IS NOT NULL AND dm_employee_low_id IS NULL AND dm_employee_high_id IS NULL)"])
add('conversation_members','collaboration','채팅 참여자/읽음 위치', '''
conversation_id BIGINT UNSIGNED NOT NULL
employee_id BIGINT UNSIGNED NOT NULL
joined_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
left_at DATETIME(6) NULL
last_read_message_id BIGINT UNSIGNED NULL
last_read_at DATETIME(6) NULL
''',fks=[fk('conversation_id','conversations','id','CASCADE'),fk('employee_id','employees')],unique=[('conversation_id','employee_id')],indexes=[('employee_id','conversation_id')])
add('messages','collaboration','순서/재시도 키/스레드/원본 업무 참조', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
conversation_id BIGINT UNSIGNED NOT NULL
sender_employee_id BIGINT UNSIGNED NOT NULL
parent_message_id BIGINT UNSIGNED NULL
related_task_id BIGINT UNSIGNED NULL
kind ENUM('TEXT','TASK_CARD') NOT NULL DEFAULT 'TEXT'
body TEXT NULL
client_message_key VARCHAR(100) NOT NULL
sent_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
''',fks=[fk('conversation_id,sender_employee_id','conversation_members','conversation_id,employee_id'),fk('parent_message_id,conversation_id','messages','id,conversation_id','RESTRICT'),fk('related_task_id','work_tasks','id','RESTRICT')],unique=[('conversation_id','sender_employee_id','client_message_key'),('id','conversation_id')],indexes=[('conversation_id','id'),('parent_message_id','id')],checks=["(kind = 'TASK_CARD' AND related_task_id IS NOT NULL) OR (kind = 'TEXT' AND body IS NOT NULL)"])
add('announcements','collaboration','내부 공지. 전체/지점 공개범위', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
author_employee_id BIGINT UNSIGNED NOT NULL
title VARCHAR(200) NOT NULL
body TEXT NOT NULL
target ENUM('ALL','SELECTED_BRANCHES') NOT NULL
status ENUM('DRAFT','PUBLISHED','ARCHIVED') NOT NULL DEFAULT 'DRAFT'
is_pinned BOOLEAN NOT NULL DEFAULT FALSE
published_at DATETIME(6) NULL
created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
version BIGINT UNSIGNED NOT NULL DEFAULT 0
''',fks=[fk('author_employee_id','employees')],indexes=[('status','is_pinned','published_at')])
add('announcement_branches','collaboration','지점 대상 공지 배포', '''
announcement_id BIGINT UNSIGNED NOT NULL
branch_id BIGINT UNSIGNED NOT NULL
''',fks=[fk('announcement_id','announcements','id','CASCADE'),fk('branch_id','branches')],unique=[('announcement_id','branch_id')],indexes=[('branch_id','announcement_id')])

# Customer register / payment / passes.
add('customers','commerce','전사 공통 고객 원장; 직원 로그인과 무관', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
member_no VARCHAR(40) NOT NULL
name VARCHAR(100) NOT NULL
phone VARCHAR(30) NULL
email VARCHAR(254) NULL
status ENUM('NORMAL','RESTRICTED','INACTIVE') NOT NULL DEFAULT 'NORMAL'
status_reason VARCHAR(500) NULL
registered_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
version BIGINT UNSIGNED NOT NULL DEFAULT 0
''',unique=[('member_no',)],indexes=[('name',),('phone',),('status',)])
add('customer_service_interactions','commerce','응대 중인 특정 고객의 전 지점 이용 조회 근거와 접근 감사', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
customer_id BIGINT UNSIGNED NOT NULL
branch_id BIGINT UNSIGNED NOT NULL
employee_id BIGINT UNSIGNED NOT NULL
reason VARCHAR(500) NOT NULL
verified_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
''',fks=[fk('customer_id','customers'),fk('branch_id,employee_id','branch_memberships','branch_id,employee_id')],indexes=[('customer_id','branch_id','verified_at'),('employee_id','verified_at')])
add('customer_status_events','commerce','고객 상태/제한 변경 이력', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
customer_id BIGINT UNSIGNED NOT NULL
previous_status VARCHAR(30) NOT NULL
new_status VARCHAR(30) NOT NULL
reason VARCHAR(500) NOT NULL
actor_employee_id BIGINT UNSIGNED NOT NULL
changed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
''',fks=[fk('customer_id','customers'),fk('actor_employee_id','employees')],indexes=[('customer_id','changed_at')])
add('products','commerce','시간권/기간권/정액권 상품', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
code VARCHAR(40) NOT NULL
name VARCHAR(120) NOT NULL
kind ENUM('TIME','PERIOD','STORED_VALUE') NOT NULL
price_krw BIGINT UNSIGNED NOT NULL
included_minutes INT UNSIGNED NULL
period_days INT UNSIGNED NULL
credit_krw BIGINT UNSIGNED NULL
hourly_rate_krw BIGINT UNSIGNED NULL
valid_days INT UNSIGNED NULL
sale_status ENUM('ON_SALE','STOPPED') NOT NULL DEFAULT 'ON_SALE'
created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
version BIGINT UNSIGNED NOT NULL DEFAULT 0
''',unique=[('code',)],indexes=[('kind','sale_status')],checks=["(kind = 'TIME' AND included_minutes IS NOT NULL AND included_minutes > 0 AND period_days IS NULL AND credit_krw IS NULL AND hourly_rate_krw IS NULL) OR (kind = 'PERIOD' AND period_days IS NOT NULL AND period_days > 0 AND included_minutes IS NULL AND credit_krw IS NULL AND hourly_rate_krw IS NULL) OR (kind = 'STORED_VALUE' AND credit_krw IS NOT NULL AND hourly_rate_krw IS NOT NULL AND credit_krw > 0 AND hourly_rate_krw > 0 AND included_minutes IS NULL AND period_days IS NULL)","valid_days IS NULL OR valid_days > 0"])
add('payments','commerce','이용권·스터디룸·사물함 내부 Mock 승인/실패, 구매 지점의 원거래', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
customer_id BIGINT UNSIGNED NOT NULL
branch_id BIGINT UNSIGNED NOT NULL
product_id BIGINT UNSIGNED NULL
product_name_snapshot VARCHAR(120) NULL
purpose ENUM('PASS','ROOM','LOCKER') NOT NULL
method ENUM('CASH','CARD','TRANSFER','OTHER') NOT NULL
amount_krw BIGINT UNSIGNED NOT NULL
status ENUM('PENDING','APPROVED','FAILED','REFUNDED') NOT NULL DEFAULT 'PENDING'
idempotency_key VARCHAR(120) NOT NULL
approved_at DATETIME(6) NULL
failed_at DATETIME(6) NULL
created_by BIGINT UNSIGNED NOT NULL
created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
version BIGINT UNSIGNED NOT NULL DEFAULT 0
''',fks=[fk('customer_id','customers'),fk('branch_id','branches'),fk('product_id','products','id','RESTRICT'),fk('created_by','employees')],unique=[('idempotency_key',)],indexes=[('branch_id','status','approved_at'),('customer_id','created_at'),('product_id','approved_at')],checks=["(purpose = 'PASS' AND product_id IS NOT NULL) OR (purpose IN ('ROOM','LOCKER') AND product_id IS NULL)"])
add('passes','commerce','승인 결제 발급권, 구매 당시 상품 조건 스냅샷', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
customer_id BIGINT UNSIGNED NOT NULL
product_id BIGINT UNSIGNED NOT NULL
purchase_payment_id BIGINT UNSIGNED NOT NULL
purchase_branch_id BIGINT UNSIGNED NOT NULL
kind ENUM('TIME','PERIOD','STORED_VALUE') NOT NULL
snapshot_price_krw BIGINT UNSIGNED NOT NULL
snapshot_product_name VARCHAR(120) NOT NULL
snapshot_minutes INT UNSIGNED NULL
snapshot_period_days INT UNSIGNED NULL
snapshot_credit_krw BIGINT UNSIGNED NULL
snapshot_hourly_rate_krw BIGINT UNSIGNED NULL
snapshot_valid_days INT UNSIGNED NULL
remaining_minutes INT UNSIGNED NULL
remaining_credit_krw BIGINT UNSIGNED NULL
status ENUM('ACTIVE','EXHAUSTED','EXPIRED','REFUNDED') NOT NULL DEFAULT 'ACTIVE'
started_at DATETIME(6) NOT NULL
expires_at DATETIME(6) NULL
created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
version BIGINT UNSIGNED NOT NULL DEFAULT 0
''',fks=[fk('customer_id','customers'),fk('product_id','products'),fk('purchase_payment_id','payments'),fk('purchase_branch_id','branches')],unique=[('purchase_payment_id',)],indexes=[('customer_id','status','expires_at'),('purchase_branch_id','created_at')],checks=["(kind = 'TIME' AND snapshot_minutes IS NOT NULL AND snapshot_minutes > 0 AND remaining_minutes IS NOT NULL AND snapshot_credit_krw IS NULL AND remaining_credit_krw IS NULL) OR (kind = 'PERIOD' AND snapshot_period_days IS NOT NULL AND snapshot_period_days > 0 AND remaining_minutes IS NULL AND remaining_credit_krw IS NULL) OR (kind = 'STORED_VALUE' AND snapshot_credit_krw IS NOT NULL AND snapshot_hourly_rate_krw IS NOT NULL AND snapshot_credit_krw > 0 AND snapshot_hourly_rate_krw > 0 AND remaining_credit_krw IS NOT NULL AND remaining_minutes IS NULL)"])
add('refunds','commerce','원거래 1회 전액 환불, 처리지점/원결제지점 분리', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
payment_id BIGINT UNSIGNED NOT NULL
processing_branch_id BIGINT UNSIGNED NOT NULL
processed_by BIGINT UNSIGNED NOT NULL
amount_krw BIGINT UNSIGNED NOT NULL
reason VARCHAR(500) NOT NULL
idempotency_key VARCHAR(120) NOT NULL
refunded_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
''',fks=[fk('payment_id','payments'),fk('processing_branch_id','branches'),fk('processed_by','employees')],unique=[('payment_id',),('idempotency_key',)],indexes=[('refunded_at',),('processing_branch_id','refunded_at')])

# Seats and spaces. Locks on resource rows are necessary for interval overlaps.
add('seats','space','지점 좌석: OCCUPIED는 활성 seat_session으로 계산', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
branch_id BIGINT UNSIGNED NOT NULL
zone VARCHAR(80) NOT NULL
seat_no VARCHAR(30) NOT NULL
kind ENUM('GENERAL','MONITOR','OTHER') NOT NULL DEFAULT 'GENERAL'
status ENUM('AVAILABLE','BLOCKED') NOT NULL DEFAULT 'AVAILABLE'
created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
version BIGINT UNSIGNED NOT NULL DEFAULT 0
''',fks=[fk('branch_id','branches')],unique=[('branch_id','seat_no'),('id','branch_id')],indexes=[('branch_id','zone','status')])
add('seat_sessions','space','일반 좌석 현장 이용 및 실측/정정 시간. 활성 고객/좌석 유일', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
customer_id BIGINT UNSIGNED NOT NULL
pass_id BIGINT UNSIGNED NOT NULL
branch_id BIGINT UNSIGNED NOT NULL
seat_id BIGINT UNSIGNED NOT NULL
status ENUM('ACTIVE','CLOSED') NOT NULL DEFAULT 'ACTIVE'
active_seat_id BIGINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN status = 'ACTIVE' THEN seat_id ELSE NULL END) STORED
active_customer_id BIGINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN status = 'ACTIVE' THEN customer_id ELSE NULL END) STORED
checked_in_at DATETIME(6) NOT NULL
effective_checked_in_at DATETIME(6) NOT NULL
checked_out_at DATETIME(6) NULL
effective_checked_out_at DATETIME(6) NULL
billed_minutes INT UNSIGNED NOT NULL DEFAULT 0
billed_krw BIGINT UNSIGNED NOT NULL DEFAULT 0
overage_minutes INT UNSIGNED NOT NULL DEFAULT 0
overage_krw BIGINT UNSIGNED NOT NULL DEFAULT 0
exception_actor_id BIGINT UNSIGNED NULL
exception_reason VARCHAR(500) NULL
start_request_key VARCHAR(120) NOT NULL
end_request_key VARCHAR(120) NULL
created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
version BIGINT UNSIGNED NOT NULL DEFAULT 0
''',fks=[fk('customer_id','customers'),fk('pass_id','passes'),fk('branch_id','branches'),fk('seat_id,branch_id','seats','id,branch_id'),fk('exception_actor_id','employees','id','SET NULL')],unique=[('active_seat_id',),('active_customer_id',),('start_request_key',),('end_request_key',)],indexes=[('branch_id','status','checked_in_at'),('pass_id','checked_in_at')],checks=["(status = 'ACTIVE' AND checked_out_at IS NULL AND effective_checked_out_at IS NULL) OR (status = 'CLOSED' AND checked_out_at IS NOT NULL AND effective_checked_out_at IS NOT NULL)","effective_checked_out_at IS NULL OR effective_checked_out_at >= effective_checked_in_at"])
add('seat_session_moves','space','같은 지점 내 좌석 이동 이력', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
seat_session_id BIGINT UNSIGNED NOT NULL
from_seat_id BIGINT UNSIGNED NOT NULL
to_seat_id BIGINT UNSIGNED NOT NULL
actor_employee_id BIGINT UNSIGNED NULL
reason VARCHAR(500) NULL
request_key VARCHAR(120) NOT NULL
moved_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
''',fks=[fk('seat_session_id','seat_sessions'),fk('from_seat_id','seats'),fk('to_seat_id','seats'),fk('actor_employee_id','employees','id','SET NULL')],unique=[('request_key',)],indexes=[('seat_session_id','moved_at')],checks=['from_seat_id <> to_seat_id'])
add('seat_session_corrections','space','시간 원본/정정값 및 사용량 차이의 변경 이력', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
seat_session_id BIGINT UNSIGNED NOT NULL
actor_employee_id BIGINT UNSIGNED NOT NULL
before_start_at DATETIME(6) NOT NULL
before_end_at DATETIME(6) NOT NULL
after_start_at DATETIME(6) NOT NULL
after_end_at DATETIME(6) NOT NULL
before_billed_minutes INT UNSIGNED NOT NULL
before_billed_krw BIGINT UNSIGNED NOT NULL
after_billed_minutes INT UNSIGNED NOT NULL
after_billed_krw BIGINT UNSIGNED NOT NULL
reason VARCHAR(500) NOT NULL
request_key VARCHAR(120) NOT NULL
created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
''',fks=[fk('seat_session_id','seat_sessions'),fk('actor_employee_id','employees')],unique=[('request_key',)],indexes=[('seat_session_id','created_at')],checks=['after_end_at >= after_start_at'])
add('pass_balance_events','commerce','발급권 차감·보정의 불변 원장, 세션/정정 단일 참조', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
pass_id BIGINT UNSIGNED NOT NULL
seat_session_id BIGINT UNSIGNED NULL
correction_id BIGINT UNSIGNED NULL
kind ENUM('USE','ADJUST','RESTORE') NOT NULL
delta_minutes BIGINT NOT NULL DEFAULT 0
delta_krw BIGINT NOT NULL DEFAULT 0
actor_employee_id BIGINT UNSIGNED NULL
reason VARCHAR(500) NULL
event_key VARCHAR(120) NOT NULL
created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
''',fks=[fk('pass_id','passes'),fk('seat_session_id','seat_sessions'),fk('correction_id','seat_session_corrections'),fk('actor_employee_id','employees','id','SET NULL')],unique=[('event_key',)],indexes=[('pass_id','created_at'),('seat_session_id','kind')],checks=['NOT (seat_session_id IS NOT NULL AND correction_id IS NOT NULL)'])
add('rooms','space','스터디룸/정원/30분당 요금', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
branch_id BIGINT UNSIGNED NOT NULL
room_no VARCHAR(40) NOT NULL
name VARCHAR(120) NOT NULL
capacity SMALLINT UNSIGNED NOT NULL
rate_per_30min_krw BIGINT UNSIGNED NOT NULL
status ENUM('AVAILABLE','BLOCKED') NOT NULL DEFAULT 'AVAILABLE'
created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
version BIGINT UNSIGNED NOT NULL DEFAULT 0
''',fks=[fk('branch_id','branches')],unique=[('branch_id','room_no'),('id','branch_id')],checks=['capacity > 0','rate_per_30min_krw > 0'])
add('room_weekly_hours','space','월~일 룸 시간; 익일 종료 지원/날짜별 휴무 미지원', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
room_id BIGINT UNSIGNED NOT NULL
weekday TINYINT UNSIGNED NOT NULL
is_closed BOOLEAN NOT NULL DEFAULT FALSE
opens_minute SMALLINT UNSIGNED NULL
closes_minute SMALLINT UNSIGNED NULL
''',fks=[fk('room_id','rooms','id','CASCADE')],unique=[('room_id','weekday')],checks=["weekday BETWEEN 1 AND 7", "((is_closed = 1 AND opens_minute IS NULL AND closes_minute IS NULL) OR (is_closed = 0 AND opens_minute < 1440 AND closes_minute > opens_minute AND closes_minute <= 2880))"])
add('room_reservations','space','룸 30분 단위 정수 요금 선결제, start 포함/end 제외', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
room_id BIGINT UNSIGNED NOT NULL
branch_id BIGINT UNSIGNED NOT NULL
customer_id BIGINT UNSIGNED NOT NULL
payment_id BIGINT UNSIGNED NULL
start_at DATETIME(6) NOT NULL
end_at DATETIME(6) NOT NULL
rate_per_30min_snapshot_krw BIGINT UNSIGNED NOT NULL
slot_count INT UNSIGNED NOT NULL
amount_krw BIGINT UNSIGNED NOT NULL
status ENUM('PAYMENT_PENDING','CONFIRMED','PAYMENT_FAILED','IN_USE','COMPLETED','CANCELLED') NOT NULL DEFAULT 'PAYMENT_PENDING'
request_key VARCHAR(120) NOT NULL
created_by BIGINT UNSIGNED NOT NULL
created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
version BIGINT UNSIGNED NOT NULL DEFAULT 0
''',fks=[fk('room_id,branch_id','rooms','id,branch_id'),fk('customer_id','customers'),fk('payment_id','payments'),fk('created_by','employees')],unique=[('request_key',),('payment_id',)],indexes=[('room_id','start_at','end_at','status'),('branch_id','start_at','status'),('customer_id','start_at')],checks=["end_at > start_at","SECOND(start_at) = 0 AND MICROSECOND(start_at) = 0","MINUTE(start_at) IN (0, 30)","SECOND(end_at) = 0 AND MICROSECOND(end_at) = 0","MINUTE(end_at) IN (0, 30)","MOD(TIMESTAMPDIFF(SECOND, start_at, end_at), 1800) = 0","TIMESTAMPDIFF(SECOND, start_at, end_at) = slot_count * 1800","slot_count > 0","rate_per_30min_snapshot_krw > 0","amount_krw = rate_per_30min_snapshot_krw * slot_count"])
add('room_usages','space','예약된 룸 실제 사용 기록', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
reservation_id BIGINT UNSIGNED NOT NULL
started_at DATETIME(6) NOT NULL
ended_at DATETIME(6) NULL
started_by BIGINT UNSIGNED NULL
ended_by BIGINT UNSIGNED NULL
''',fks=[fk('reservation_id','room_reservations'),fk('started_by','employees','id','SET NULL'),fk('ended_by','employees','id','SET NULL')],unique=[('reservation_id',)],checks=['ended_at IS NULL OR ended_at >= started_at'])
add('lockers','space','지점 사물함', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
branch_id BIGINT UNSIGNED NOT NULL
locker_no VARCHAR(40) NOT NULL
rate_per_4weeks_krw BIGINT UNSIGNED NOT NULL
status ENUM('AVAILABLE','BLOCKED') NOT NULL DEFAULT 'AVAILABLE'
version BIGINT UNSIGNED NOT NULL DEFAULT 0
''',fks=[fk('branch_id','branches')],unique=[('branch_id','locker_no')],checks=['rate_per_4weeks_krw > 0'])
add('locker_assignments','space','사물함 배정/유료 최초 결제·연장, 날짜 구간 [start,end)', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
locker_id BIGINT UNSIGNED NOT NULL
customer_id BIGINT UNSIGNED NOT NULL
start_date DATE NOT NULL
end_date DATE NOT NULL
returned_at DATETIME(6) NULL
status ENUM('PAYMENT_PENDING','PAYMENT_FAILED','ACTIVE','RETURNED','CANCELLED') NOT NULL DEFAULT 'PAYMENT_PENDING'
assigned_by BIGINT UNSIGNED NOT NULL
last_changed_by BIGINT UNSIGNED NULL
reason VARCHAR(500) NULL
created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
version BIGINT UNSIGNED NOT NULL DEFAULT 0
''',fks=[fk('locker_id','lockers'),fk('customer_id','customers'),fk('assigned_by','employees'),fk('last_changed_by','employees','id','SET NULL')],indexes=[('locker_id','start_date','end_date','status'),('customer_id','status')],checks=['end_date > start_date'])
add('locker_assignment_charges','space','사물함 최초 결제/유료 연장: 4주(28일) 정액 요금과 원거래 보존', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
assignment_id BIGINT UNSIGNED NOT NULL
payment_id BIGINT UNSIGNED NOT NULL
charge_kind ENUM('INITIAL','EXTENSION') NOT NULL
initial_assignment_slot BIGINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN charge_kind = 'INITIAL' AND status IN ('PENDING','APPLIED') THEN assignment_id ELSE NULL END) STORED
start_date DATE NOT NULL
end_date DATE NOT NULL
charged_4week_terms INT UNSIGNED NOT NULL
rate_per_4weeks_snapshot_krw BIGINT UNSIGNED NOT NULL
amount_krw BIGINT UNSIGNED NOT NULL
status ENUM('PENDING','APPLIED','FAILED') NOT NULL DEFAULT 'PENDING'
created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
applied_at DATETIME(6) NULL
''',fks=[fk('assignment_id','locker_assignments'),fk('payment_id','payments')],unique=[('payment_id',),('initial_assignment_slot',)],indexes=[('assignment_id','created_at'),('status','created_at')],checks=['end_date > start_date','DATEDIFF(end_date, start_date) = charged_4week_terms * 28','charged_4week_terms > 0','rate_per_4weeks_snapshot_krw > 0','amount_krw = charged_4week_terms * rate_per_4weeks_snapshot_krw',"(status = 'APPLIED' AND applied_at IS NOT NULL) OR (status IN ('PENDING','FAILED') AND applied_at IS NULL)"])


# Facility / inquiries.
add('facility_types','facility','시설 유형과 기본 점검 주기', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
name VARCHAR(80) NOT NULL
default_inspection_days SMALLINT UNSIGNED NOT NULL
''',unique=[('name',)],checks=['default_inspection_days > 0'])
add('facilities','facility','지점 시설: 상태/점검 주기/마지막 완료일', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
branch_id BIGINT UNSIGNED NOT NULL
type_id BIGINT UNSIGNED NOT NULL
name VARCHAR(120) NOT NULL
location VARCHAR(200) NULL
description TEXT NULL
status ENUM('NORMAL','CHECK_NEEDED','BROKEN','OUT_OF_SERVICE') NOT NULL DEFAULT 'NORMAL'
inspection_interval_days SMALLINT UNSIGNED NOT NULL
last_inspected_on DATE NULL
created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
version BIGINT UNSIGNED NOT NULL DEFAULT 0
''',fks=[fk('branch_id','branches'),fk('type_id','facility_types')],indexes=[('branch_id','status'),('branch_id','last_inspected_on')],checks=['inspection_interval_days > 0'])
add('facility_works','facility','점검·청소·수리 이력. 작업완료 != 시설 정상', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
facility_id BIGINT UNSIGNED NOT NULL
kind ENUM('INSPECTION','CLEANING','REPAIR') NOT NULL
status ENUM('OPEN','IN_PROGRESS','DONE','CANCELLED') NOT NULL DEFAULT 'OPEN'
problem VARCHAR(500) NULL
result_note TEXT NULL
assigned_employee_id BIGINT UNSIGNED NULL
completed_by BIGINT UNSIGNED NULL
opened_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
completed_at DATETIME(6) NULL
''',fks=[fk('facility_id','facilities'),fk('assigned_employee_id','employees','id','SET NULL'),fk('completed_by','employees','id','SET NULL')],indexes=[('facility_id','opened_at'),('status','opened_at')])
add('inquiries','facility','고객 전화/현장 문의 백오피스 대리 접수', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
branch_id BIGINT UNSIGNED NOT NULL
customer_id BIGINT UNSIGNED NOT NULL
subject VARCHAR(200) NOT NULL
body TEXT NOT NULL
source ENUM('IN_PERSON','PHONE','OTHER') NOT NULL
status ENUM('RECEIVED','IN_PROGRESS','ANSWERED') NOT NULL DEFAULT 'RECEIVED'
assigned_employee_id BIGINT UNSIGNED NULL
created_by BIGINT UNSIGNED NOT NULL
answered_by BIGINT UNSIGNED NULL
answer_body TEXT NULL
answered_at DATETIME(6) NULL
created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
version BIGINT UNSIGNED NOT NULL DEFAULT 0
''',fks=[fk('branch_id','branches'),fk('customer_id','customers'),fk('assigned_employee_id','employees','id','SET NULL'),fk('created_by','employees'),fk('answered_by','employees','id','SET NULL')],indexes=[('branch_id','status','created_at'),('customer_id','created_at')])
add('inquiry_events','facility','문의 답변·상태 변경 이력', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
inquiry_id BIGINT UNSIGNED NOT NULL
actor_employee_id BIGINT UNSIGNED NOT NULL
old_status VARCHAR(30) NULL
new_status VARCHAR(30) NOT NULL
message TEXT NULL
created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
''',fks=[fk('inquiry_id','inquiries'),fk('actor_employee_id','employees')],indexes=[('inquiry_id','created_at')])

# Workforce.
add('shifts','hr','계획 근무; 지점 간 시간 충돌은 잠금으로 검증', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
employee_id BIGINT UNSIGNED NOT NULL
branch_id BIGINT UNSIGNED NOT NULL
starts_at DATETIME(6) NOT NULL
ends_at DATETIME(6) NOT NULL
planned_break_minutes SMALLINT UNSIGNED NOT NULL DEFAULT 0
status ENUM('SCHEDULED','CANCELLED') NOT NULL DEFAULT 'SCHEDULED'
created_by BIGINT UNSIGNED NOT NULL
created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
version BIGINT UNSIGNED NOT NULL DEFAULT 0
''',fks=[fk('branch_id,employee_id','branch_memberships','branch_id,employee_id'),fk('created_by','employees')],indexes=[('employee_id','starts_at','ends_at'),('branch_id','starts_at','status')],checks=['ends_at > starts_at'])
add('shift_events','hr','근무 계획 변경의 원본/후 이력', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
shift_id BIGINT UNSIGNED NOT NULL
actor_employee_id BIGINT UNSIGNED NOT NULL
event_type ENUM('CREATED','CHANGED','CANCELLED','SWAPPED') NOT NULL
before_json JSON NULL
after_json JSON NULL
reason VARCHAR(500) NULL
created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
''',fks=[fk('shift_id','shifts'),fk('actor_employee_id','employees')],indexes=[('shift_id','created_at')])
add('attendance_records','hr','실제 출퇴근은 계획 근무와 독립', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
employee_id BIGINT UNSIGNED NOT NULL
branch_id BIGINT UNSIGNED NOT NULL
shift_id BIGINT UNSIGNED NULL
clock_in_at DATETIME(6) NOT NULL
clock_out_at DATETIME(6) NULL
status ENUM('WORKING','LEFT') NOT NULL DEFAULT 'WORKING'
active_employee_id BIGINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN status = 'WORKING' THEN employee_id ELSE NULL END) STORED
created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
version BIGINT UNSIGNED NOT NULL DEFAULT 0
''',fks=[fk('branch_id,employee_id','branch_memberships','branch_id,employee_id'),fk('shift_id','shifts')],unique=[('active_employee_id',)],indexes=[('employee_id','clock_in_at'),('branch_id','clock_in_at')],checks=["(status = 'WORKING' AND clock_out_at IS NULL) OR (status = 'LEFT' AND clock_out_at IS NOT NULL)","clock_out_at IS NULL OR clock_out_at >= clock_in_at"])
add('attendance_breaks','hr','출근 중 휴게 구간', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
attendance_id BIGINT UNSIGNED NOT NULL
started_at DATETIME(6) NOT NULL
ended_at DATETIME(6) NULL
''',fks=[fk('attendance_id','attendance_records')],indexes=[('attendance_id','started_at')],checks=['ended_at IS NULL OR ended_at >= started_at'])
add('attendance_corrections','hr','직원 신청, 관리자 승인 시 실제 근태 반영', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
attendance_id BIGINT UNSIGNED NOT NULL
requested_by BIGINT UNSIGNED NOT NULL
correction_kind ENUM('CLOCK','BREAK') NOT NULL DEFAULT 'CLOCK'
break_id BIGINT UNSIGNED NULL
before_clock_in_at DATETIME(6) NULL
before_clock_out_at DATETIME(6) NULL
after_clock_in_at DATETIME(6) NULL
after_clock_out_at DATETIME(6) NULL
before_break_start_at DATETIME(6) NULL
before_break_end_at DATETIME(6) NULL
after_break_start_at DATETIME(6) NULL
after_break_end_at DATETIME(6) NULL
reason VARCHAR(500) NOT NULL
status ENUM('REQUESTED','APPROVED','REJECTED','CANCELLED') NOT NULL DEFAULT 'REQUESTED'
decided_by BIGINT UNSIGNED NULL
decided_at DATETIME(6) NULL
created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
version BIGINT UNSIGNED NOT NULL DEFAULT 0
''',fks=[fk('attendance_id','attendance_records'),fk('break_id','attendance_breaks'),fk('requested_by','employees'),fk('decided_by','employees','id','SET NULL')],indexes=[('attendance_id','created_at'),('status','created_at')],checks=["(correction_kind = 'CLOCK' AND after_clock_in_at IS NOT NULL AND break_id IS NULL) OR (correction_kind = 'BREAK' AND break_id IS NOT NULL AND after_break_start_at IS NOT NULL)", 'after_clock_out_at IS NULL OR after_clock_out_at >= after_clock_in_at', 'after_break_end_at IS NULL OR after_break_end_at >= after_break_start_at'])
add('leave_grants','hr','직원·연도 공통 휴가 부여, 원부여 지점 유지', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
employee_id BIGINT UNSIGNED NOT NULL
origin_branch_id BIGINT UNSIGNED NOT NULL
grant_year SMALLINT UNSIGNED NOT NULL
valid_from DATE NOT NULL
valid_through DATE NOT NULL
granted_days DECIMAL(7,1) NOT NULL
remaining_days DECIMAL(7,1) NOT NULL
reason VARCHAR(500) NOT NULL
granted_by BIGINT UNSIGNED NOT NULL
created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
version BIGINT UNSIGNED NOT NULL DEFAULT 0
''',fks=[fk('employee_id','employees'),fk('origin_branch_id','branches'),fk('granted_by','employees')],indexes=[('employee_id','grant_year','valid_through','id')],checks=['valid_through >= valid_from', 'granted_days > 0','remaining_days >= 0','MOD(granted_days * 2, 1) = 0','MOD(remaining_days * 2, 1) = 0'])
add('leave_requests','hr','기간+총일수 휴가 신청 (반차 시간대/비연속 날짜는 제외)', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
employee_id BIGINT UNSIGNED NOT NULL
request_year SMALLINT UNSIGNED NOT NULL
starts_on DATE NOT NULL
ends_on DATE NOT NULL
requested_days DECIMAL(7,1) NOT NULL
reason VARCHAR(500) NOT NULL
status ENUM('REQUESTED','APPROVED','REJECTED','CANCELLED') NOT NULL DEFAULT 'REQUESTED'
decided_by BIGINT UNSIGNED NULL
decided_at DATETIME(6) NULL
created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
version BIGINT UNSIGNED NOT NULL DEFAULT 0
''',fks=[fk('employee_id','employees'),fk('decided_by','employees','id','SET NULL')],indexes=[('employee_id','starts_on','ends_on','status')],checks=['ends_on >= starts_on','YEAR(starts_on) = request_year AND YEAR(ends_on) = request_year','requested_days > 0','MOD(requested_days * 2, 1) = 0'])
add('leave_allocations','hr','승인 시 소비한 각 부여 배분과 취소 복원 마커', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
leave_request_id BIGINT UNSIGNED NOT NULL
leave_grant_id BIGINT UNSIGNED NOT NULL
allocated_days DECIMAL(7,1) NOT NULL
restored_at DATETIME(6) NULL
''',fks=[fk('leave_request_id','leave_requests'),fk('leave_grant_id','leave_grants')],unique=[('leave_request_id','leave_grant_id')],checks=['allocated_days > 0','MOD(allocated_days * 2, 1) = 0'])
add('leave_balance_events','hr','휴가 부여/보정/소비/복원 불변 원장', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
leave_grant_id BIGINT UNSIGNED NOT NULL
leave_request_id BIGINT UNSIGNED NULL
event_type ENUM('GRANT','ADJUST','USE','RESTORE') NOT NULL
delta_days DECIMAL(7,1) NOT NULL
reason VARCHAR(500) NULL
actor_employee_id BIGINT UNSIGNED NOT NULL
event_key VARCHAR(120) NOT NULL
created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
''',fks=[fk('leave_grant_id','leave_grants'),fk('leave_request_id','leave_requests'),fk('actor_employee_id','employees')],unique=[('event_key',)],indexes=[('leave_grant_id','created_at'),('leave_request_id','event_type')],checks=['delta_days <> 0','MOD(delta_days * 2, 1) = 0'])
add('shift_swaps','hr','기존 근무 인원/시간 대체 신청, 승인 시 원 근무 업데이트', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
original_shift_id BIGINT UNSIGNED NOT NULL
requested_by BIGINT UNSIGNED NOT NULL
replacement_employee_id BIGINT UNSIGNED NOT NULL
proposed_starts_at DATETIME(6) NOT NULL
proposed_ends_at DATETIME(6) NOT NULL
reason VARCHAR(500) NOT NULL
status ENUM('REQUESTED','APPROVED','REJECTED','CANCELLED') NOT NULL DEFAULT 'REQUESTED'
decided_by BIGINT UNSIGNED NULL
decided_at DATETIME(6) NULL
created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
version BIGINT UNSIGNED NOT NULL DEFAULT 0
''',fks=[fk('original_shift_id','shifts'),fk('requested_by','employees'),fk('replacement_employee_id','employees'),fk('decided_by','employees','id','SET NULL')],indexes=[('original_shift_id','status'),('replacement_employee_id','proposed_starts_at')],checks=['proposed_ends_at > proposed_starts_at'])

# AI is a consumer; no direct SQL / generated internal engine tables.
add('ai_threads','ai','별도 AI 대화. 팀 채팅과 데이터·권한 분리', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
employee_id BIGINT UNSIGNED NOT NULL
created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
last_used_at DATETIME(6) NULL
''',fks=[fk('employee_id','employees')],indexes=[('employee_id','created_at')])
add('ai_requests','ai','DomainLens 소비 호출 상태/계약 버전, 결과 캐시 비저장', '''
id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
thread_id BIGINT UNSIGNED NOT NULL
engine_request_ref VARCHAR(150) NULL
engine_contract_version VARCHAR(80) NULL
status ENUM('RECEIVED','CLARIFICATION','SUCCEEDED','EMPTY','DENIED','UNSUPPORTED','FAILED','TIMED_OUT','CANCELLED') NOT NULL DEFAULT 'RECEIVED'
requested_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
finished_at DATETIME(6) NULL
error_code VARCHAR(80) NULL
''',fks=[fk('thread_id','ai_threads')],indexes=[('thread_id','requested_at'),('status','requested_at')])

# Standard Spring Session JDBC schema maintained by the application dependency.
add('SPRING_SESSION','session','Spring Session JDBC 표준 테이블 (프레임워크 공식 DDL 버전 확인)', '''
PRIMARY_ID CHAR(36) NOT NULL
SESSION_ID CHAR(36) NOT NULL
CREATION_TIME BIGINT NOT NULL
LAST_ACCESS_TIME BIGINT NOT NULL
MAX_INACTIVE_INTERVAL INT NOT NULL
EXPIRY_TIME BIGINT NOT NULL
PRINCIPAL_NAME VARCHAR(100) NULL
''',unique=[('PRIMARY_ID',),('SESSION_ID',)],indexes=[('EXPIRY_TIME',),('PRINCIPAL_NAME',)])
add('SPRING_SESSION_ATTRIBUTES','session','Spring Session JDBC 저장된 세션 속성', '''
SESSION_PRIMARY_ID CHAR(36) NOT NULL
ATTRIBUTE_NAME VARCHAR(200) NOT NULL
ATTRIBUTE_BYTES BLOB NOT NULL
''',fks=[fk('SESSION_PRIMARY_ID','SPRING_SESSION','PRIMARY_ID','CASCADE')],unique=[('SESSION_PRIMARY_ID','ATTRIBUTE_NAME')])

# Verify dependency/order/columns before writing.
assert len(D)>50, len(D)
for name,t in D.items():
    cols=[x.split()[0] for x in t['cols']]
    assert len(cols)==len(set(cols)),name
    for local,other,remote,act in t['fks']:
        assert other in D,(name,other)
        assert list(D).index(other)<=list(D).index(name),(name,other)
        othercols=[x.split()[0] for x in D[other]['cols']]
        assert set(local)<=set(cols), (name,local)
        assert set(remote)<=set(othercols),(name,other,remote)

# Note: CREATE TABLE indexes share unique names only inside each table; name length limit 64.
def ident(prefix, table, i):
    seed=f'{prefix}_{table}_{i}'
    if len(seed)<=64:return seed
    return seed[:53]+'_'+hashlib.sha1(seed.encode()).hexdigest()[:10]

def key(cols):return ', '.join('`'+v+'`' for v in cols)

out=['-- OpenStudy / MySQL 8.4 InnoDB / initial draft v0.4 (2026-10-18)',
'-- NOTE: initial schema, not a validated production migration; review docs before applying.',
'-- All DATETIME(6) values are UTC; configure database connections and MySQL session time_zone = +00:00.',
'-- Local business-day/month calculations and weekly operating hours use Asia/Seoul in application logic.',
'-- No customer login/QR/real PG/social login/attachment delivery in the stated scope.',
'-- Requires MySQL 8.4 CHECK constraints and generated columns.',
'CREATE DATABASE IF NOT EXISTS `openstudy` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;',
'USE `openstudy`;',
"SET time_zone = '+00:00';",'']
for table,d in D.items():
    parts=['  `'+c.split(' ',1)[0]+'` '+c.split(' ',1)[1] for c in d['cols']]
    primary=('id',) if 'id' in [x.split()[0] for x in d['cols']] else ()
    if table=='SPRING_SESSION':primary=('PRIMARY_ID',)
    if table=='SPRING_SESSION_ATTRIBUTES': primary=('SESSION_PRIMARY_ID','ATTRIBUTE_NAME')
    if table=='permission_preset_grants': primary=('preset_id','permission_id')
    if table=='membership_permission_grants': primary=('membership_id','permission_id')
    if table=='conversation_members': primary=('conversation_id','employee_id')
    if table=='announcement_branches': primary=('announcement_id','branch_id')
    if primary:parts.append('  PRIMARY KEY ('+key(primary)+')')
    unq=[k for k in d['unique'] if k!=primary]
    for i,k in enumerate(unq,1): parts.append('  UNIQUE KEY `'+ident('uk',table,i)+'` ('+key(k)+')')
    for i,k in enumerate(d['indexes'],1):parts.append('  KEY `'+ident('ix',table,i)+'` ('+key(k)+')')
    for i,(local,other,remote,act) in enumerate(d['fks'],1):
        parts.append('  CONSTRAINT `'+ident('fk',table,i)+'` FOREIGN KEY ('+key(local)+') REFERENCES `'+other+'` ('+key(remote)+') ON DELETE '+act)
    for i,ch in enumerate(d['checks'],1):parts.append('  CONSTRAINT `'+ident('ck',table,i)+'` CHECK ('+ch+')')
    out.append('-- '+d['group'].upper()+': '+d['desc'])
    out.append('CREATE TABLE `'+table+'` (\n'+',\n'.join(parts)+'\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT=\''+d['desc'].replace("'","''")[:100]+'\';\n')
# Avoid wrong app-level guarantees: read explicit caveats.
out+=['-- TRANSACTION BOUNDARIES (not guaranteed by FK/UNIQUE alone):',
'-- room_reservations: price = 30-minute slot count * KRW rate; payment/confirmation atomic.',
'-- room_reservations: SELECT room FOR UPDATE, then assert no overlap [start,end) for CONFIRMED/IN_USE before APPROVE.',
'-- locker_assignments: SELECT locker FOR UPDATE, then assert no overlapping ACTIVE date intervals.',
'-- Locker billing: charged_4week_terms * rate_per_4weeks_snapshot_krw. 1 term = exactly 28 days.',
'-- Locker rental/charge date ranges use [start_date,end_date), end exclusive.',
'--   initial end = start_date.plusDays(28 * terms); extension end = current end_date.plusDays(28 * added_terms).',
'--   e.g. 2027-01-31 + 4 weeks = 2027-02-28; + 8 weeks total = 2027-03-28.',
'-- CHECK asserts each charge interval is 28 * charged_4week_terms days; application validates contiguous charges and parent assignment.',
'-- locker_assignment_charges INITIAL: payment APPROVED -> charge APPLIED + assignment ACTIVE in one transaction.',
'-- locker_assignment_charges EXTENSION: payment APPROVED -> charge APPLIED + extend assignment.end_date atomically.',
'-- FAILED locker payments cannot activate/extend a rental; retry is controlled by payments.idempotency_key.',
'-- Locker 4-week term billing confirmed; return/cancellation refund eligibility remains undecided: see README.',
'-- shifts: SELECT employee FOR UPDATE, then assert no overlapping scheduled shifts across all branches.',
'-- attendance_breaks: SELECT attendance_record FOR UPDATE, then reject overlapping break intervals/outside work.',
'-- cross-entity coherency: payment->pass/reservation, branch->seat/room, passes.customer, invitation approval.',
'-- all write workflows require authorization recheck, transaction, idempotency key/version, audit as relevant.',
'-- Spring Session max absolute auth age 12h is enforced by application, not by SPRING_SESSION expiry alone.',
'-- employee email immutability is an API/service contract, not a DDL update restriction.',
'-- account suspension/logout invalidates Spring sessions + SSE stream from app layer.',
'-- permission_presets do not dynamically grant rights; copy into membership_permission_grants.',
'-- invitation sender generates raw token only in transient memory, persists digest/revision, never outbox payload.',
'-- schema changes should be transferred to Flyway V1__initial_schema.sql after review, not rerun on populated DB.',
]
(base/'openstudy_schema_v0_4.sql').write_text('\n'.join(out),encoding='utf-8')

# Physical ER diagrams -- columns reflect actual DDL, relationship labels from FKs.
def mermaid_type(sql_type):
    t=sql_type.split('(',1)[0].upper()
    return re.sub(r'[^a-zA-Z0-9_]','_',t)
def mermaid_entity(name,d,include_fields=True):
    cols=[]
    for c in d['cols']:
        colname=c.split()[0]; dtype=c.split()[1]
        ispk= colname=='id' or (name=='SPRING_SESSION' and colname=='PRIMARY_ID') or (name=='SPRING_SESSION_ATTRIBUTES' and colname in ['SESSION_PRIMARY_ID','ATTRIBUTE_NAME']) or (name in ('permission_preset_grants','membership_permission_grants','conversation_members','announcement_branches') and colname in ([x for k in d['unique'] for x in k][0:2]))
        isfk=any(colname in f[0] for f in d['fks'])
        attrs=' PK,FK' if (ispk and isfk) else (' PK' if ispk else (' FK' if isfk else ''))
        cols.append(f'    {mermaid_type(dtype)} {colname}{attrs}')
    if not include_fields:
        cols=[x for x in cols if x.endswith(' PK') or x.endswith(' FK')]
        if not cols:cols=['    string name']
    return '  '+name+' {\n'+'\n'.join(cols)+'\n  }'

# grouped physical files repeat foreign entities as compact reference node
sections=[('01_access','access'),('02_collaboration','collaboration'),('03_commerce','commerce'),('04_space','space'),('05_facility','facility'),('06_hr','hr'),('07_ai_session','ai')]
for filename,group in sections:
    chosen={n for n,v in D.items() if v['group']==group}
    if group=='ai':chosen|={n for n,v in D.items() if v['group']=='session'}
    extra={f[1] for n in chosen for f in D[n]['fks'] if f[1] not in chosen}
    parts=['erDiagram']
    for n in D:
        if n in chosen or n in extra:
            parts.append(mermaid_entity(n,D[n],include_fields=n in chosen))
    edges=set()
    for n in chosen:
        for local,other,remote,act in D[n]['fks']:
            label='_'.join(local)
            edge=f'  {other} ||--o{{ {n} : "{label}"'
            edges.add(edge)
    parts.extend(sorted(edges))
    (base/'diagrams'/f'{filename}_physical.mmd').write_text('\n'.join(parts)+'\n',encoding='utf-8')

# Full physical ERD. Large; sectional diagrams are meant for actual review.
full=['erDiagram']
for n,d in D.items():full.append(mermaid_entity(n,d))
for n,d in D.items():
    for local,other,remote,act in d['fks']:
        full.append(f'  {other} ||--o{{ {n} : "{"_".join(local)}"')
(base/'openstudy_physical_erd.mmd').write_text('\n'.join(full)+'\n',encoding='utf-8')

logical='''erDiagram
    %% Logical model: business concepts, not one-to-one physical table mapping.
    BRANCH ||--o{ MEMBERSHIP : "staff assigned"
    EMPLOYEE ||--o{ MEMBERSHIP : "works at"
    MEMBERSHIP ||--o{ ACTION_GRANT : "snapshot permissions"
    PERMISSION_PRESET ||--o{ ACTION_GRANT : "copy at assignment"
    BRANCH ||--o{ STAFF_INVITATION : "invites into"
    STAFF_INVITATION ||--o{ INVITE_SEND_JOB : "revisioned delivery"
    EMPLOYEE ||--o{ TASK : "assigned to"
    BRANCH ||--o{ TASK : "owns"
    TASK ||--o{ TASK_CHANGE : "history and checklist"
    EMPLOYEE ||--o{ CHAT_MEMBERSHIP : "participates"
    CHAT_ROOM ||--o{ CHAT_MEMBERSHIP : "includes"
    CHAT_ROOM ||--o{ MESSAGE : "contains"
    MESSAGE }o--o| TASK : "card references live task"
    CUSTOMER ||--o{ PAYMENT : "pays via operator"
    BRANCH ||--o{ PAYMENT : "purchase location"
    PRODUCT ||--o{ PASS : "snapshot at purchase"
    PAYMENT ||--o| PASS : "approved issuance"
    PAYMENT ||--o| REFUND : "full refund"
    CUSTOMER ||--o{ PASS : "owns"
    PASS ||--o{ PASS_MOVEMENT : "consume or correct"
    PASS ||--o{ SEAT_SESSION : "seat usage"
    BRANCH ||--o{ SEAT : "contains"
    SEAT ||--o{ SEAT_SESSION : "occupancy"
    SEAT_SESSION ||--o{ SEAT_CORRECTION : "preserves original"
    BRANCH ||--o{ STUDY_ROOM : "contains"
    STUDY_ROOM ||--o{ ROOM_BOOKING : "30-min slot pricing"
    CUSTOMER ||--o{ ROOM_BOOKING : "reservation"
    PAYMENT ||--o| ROOM_BOOKING : "prepaid"
    ROOM_BOOKING ||--o| ROOM_USAGE : "actual usage"
    BRANCH ||--o{ LOCKER : "contains"
    LOCKER ||--o{ LOCKER_RENTAL : "allocates fixed 4-week terms"
    CUSTOMER ||--o{ LOCKER_RENTAL : "uses"
    LOCKER_RENTAL ||--o{ LOCKER_CHARGE : "4-week fee at purchase or extension"
    PAYMENT ||--o| LOCKER_CHARGE : "Mock payment proof"
    BRANCH ||--o{ FACILITY : "contains"
    FACILITY ||--o{ FACILITY_WORK : "checks repairs"
    CUSTOMER ||--o{ CUSTOMER_INQUIRY : "asks"
    BRANCH ||--o{ CUSTOMER_INQUIRY : "handles"
    ANNOUNCEMENT }o--o{ BRANCH : "targets branch(s)"
    EMPLOYEE ||--o{ SHIFT : "planned"
    SHIFT |o--o{ ATTENDANCE : "optional linkage"
    ATTENDANCE ||--o{ BREAK_PERIOD : "actual breaks"
    ATTENDANCE ||--o{ ATTENDANCE_CORRECTION : "approved edit"
    EMPLOYEE ||--o{ LEAVE_GRANT : "annual entitlement"
    EMPLOYEE ||--o{ LEAVE_REQUEST : "requests"
    LEAVE_REQUEST ||--o{ LEAVE_ALLOCATION : "debits and restores"
    LEAVE_GRANT ||--o{ LEAVE_ALLOCATION : "funds request"
    SHIFT ||--o{ SHIFT_SWAP : "replacement approval"
    EMPLOYEE ||--o{ AI_CONVERSATION : "private analysis"
    AI_CONVERSATION ||--o{ AI_REQUEST : "restricted sales question"
    EMPLOYEE ||--o{ AUTH_SESSION : "Spring Session JDBC"
'''
(base/'openstudy_logical_erd.mmd').write_text(logical,encoding='utf-8')

print('TABLES:',len(D),'GROUPS:',dict(Counter(d['group'] for d in D.values())))
print('FOREIGN KEYS:',sum(len(d['fks']) for d in D.values()),'OUTPUT:',(base/'openstudy_schema_v0_4.sql').stat().st_size)
