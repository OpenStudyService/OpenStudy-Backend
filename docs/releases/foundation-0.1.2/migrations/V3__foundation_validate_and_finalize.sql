-- All preflight checks precede data changes/DDL. MySQL DDL is not transactional.
-- For legacy data, target V2 first; see docs/db/foundation-upgrade.md.
DROP PROCEDURE IF EXISTS foundation_preflight;
DELIMITER $$
CREATE PROCEDURE foundation_preflight()
BEGIN
  IF EXISTS(SELECT 1 FROM foundation_legacy_review WHERE confirmed=FALSE) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='FOUNDATION_MAPPING_REQUIRED: review legacy products/invitations at Flyway target=2';
  END IF;
  IF EXISTS(SELECT 1 FROM employees WHERE TRIM(email)='')
     OR EXISTS(SELECT 1 FROM employee_invitations WHERE TRIM(invitee_email)='') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='FOUNDATION_INVALID_EMAIL: empty normalized email';
  END IF;
  IF EXISTS(SELECT LOWER(TRIM(email)) COLLATE utf8mb4_0900_as_ci FROM employees
       GROUP BY LOWER(TRIM(email)) COLLATE utf8mb4_0900_as_ci HAVING COUNT(*)>1)
     OR EXISTS(SELECT branch_id,LOWER(TRIM(invitee_email)) COLLATE utf8mb4_0900_as_ci
       FROM employee_invitations WHERE active_invitation_slot=1
       GROUP BY branch_id,LOWER(TRIM(invitee_email)) COLLATE utf8mb4_0900_as_ci HAVING COUNT(*)>1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='FOUNDATION_EMAIL_COLLISION: resolve normalized email duplicates';
  END IF;
  IF EXISTS(SELECT 1 FROM products p LEFT JOIN product_categories c ON c.id=p.category_id
       WHERE c.id IS NULL OR c.kind<>p.kind OR p.price_krw=0
       OR (p.kind='PERIOD' AND p.valid_days IS NOT NULL)
       OR NOT EXISTS(SELECT 1 FROM product_branches b WHERE b.product_id=p.id)) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='FOUNDATION_INVALID_PRODUCT: review category/kind/price/period validity/explicit branches';
  END IF;
  IF EXISTS(SELECT 1 FROM employee_invitations WHERE invitee_name IS NULL OR TRIM(invitee_name)=''
       OR (status IN ('ACCEPTED','APPROVED','REJECTED') AND (accepted_employee_id IS NULL OR accepted_at IS NULL))
       OR (status NOT IN ('ACCEPTED','APPROVED','REJECTED') AND (accepted_employee_id IS NOT NULL OR accepted_at IS NOT NULL))
       OR (status='APPROVED' AND (approved_by IS NULL OR approved_at IS NULL))
       OR (status<>'APPROVED' AND (approved_by IS NOT NULL OR approved_at IS NOT NULL))
       OR (status='REJECTED' AND (rejected_by IS NULL OR rejected_at IS NULL OR rejection_reason IS NULL OR TRIM(rejection_reason)=''))
       OR (status<>'REJECTED' AND (rejected_by IS NOT NULL OR rejected_at IS NOT NULL OR rejection_reason IS NOT NULL))
       OR (status='CANCELLED' AND (cancelled_by IS NULL OR cancelled_at IS NULL))
       OR (status<>'CANCELLED' AND (cancelled_by IS NOT NULL OR cancelled_at IS NOT NULL))
       OR (status<>'PENDING' AND token_hash IS NOT NULL)) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='FOUNDATION_INVALID_INVITATION: review name/state/actors/timestamps/token disposal';
  END IF;
  IF EXISTS(SELECT token_hash FROM employee_invitations WHERE token_hash IS NOT NULL GROUP BY token_hash HAVING COUNT(*)>1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='FOUNDATION_DUPLICATE_TOKEN_HASH';
  END IF;
  IF EXISTS(SELECT 1 FROM permission_definitions WHERE code IN
       ('members:write','operations:write','payments:read','payments:write','sales:read','facilities:write','notices:write','ai:read') AND is_admin_only<>FALSE)
     OR EXISTS(SELECT 1 FROM permission_definitions WHERE code='people:manage' AND is_admin_only<>TRUE) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='FOUNDATION_PERMISSION_SEED_CONFLICT: review admin-only flags';
  END IF;
  IF EXISTS(SELECT 1 FROM permission_presets p WHERE
       (p.name='일반 직원 기본' AND p.role='STAFF' AND
          (p.is_system<>TRUE OR EXISTS(SELECT 1 FROM permission_preset_grants g WHERE g.preset_id=p.id)))
       OR (p.name='지점 관리자 기본' AND p.role='MANAGER' AND
          (p.is_system<>TRUE OR (SELECT COUNT(*) FROM permission_preset_grants g WHERE g.preset_id=p.id)<>9
           OR EXISTS(SELECT 1 FROM permission_preset_grants g JOIN permission_definitions d ON d.id=g.permission_id
             WHERE g.preset_id=p.id AND d.code NOT IN
             ('members:write','operations:write','payments:read','payments:write','sales:read','facilities:write','notices:write','people:manage','ai:read'))))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='FOUNDATION_PRESET_SEED_CONFLICT: existing named preset differs from Q5';
  END IF;
END$$
DELIMITER ;
CALL foundation_preflight();
DROP PROCEDURE foundation_preflight;

ALTER TABLE employees MODIFY email VARCHAR(254) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_ci NOT NULL;
ALTER TABLE employee_invitations MODIFY invitee_email VARCHAR(254) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_ci NOT NULL;
UPDATE employees SET email=LOWER(TRIM(email)),updated_at=updated_at;
UPDATE employee_invitations SET invitee_email=LOWER(TRIM(invitee_email)),updated_at=updated_at;
ALTER TABLE employee_invitations
  MODIFY invitee_name VARCHAR(100) NOT NULL,
  ADD UNIQUE KEY uk_invitations_token_hash(token_hash),
  ADD CONSTRAINT ck_invitations_name CHECK(CHAR_LENGTH(TRIM(invitee_name))>0),
  ADD CONSTRAINT ck_invitations_acceptance CHECK(
    (status IN ('ACCEPTED','APPROVED','REJECTED') AND accepted_employee_id IS NOT NULL AND accepted_at IS NOT NULL)
    OR (status IN ('PENDING','CANCELLED','EXPIRED') AND accepted_employee_id IS NULL AND accepted_at IS NULL)),
  ADD CONSTRAINT ck_invitations_approval CHECK(
    (status='APPROVED' AND approved_by IS NOT NULL AND approved_at IS NOT NULL)
    OR (status<>'APPROVED' AND approved_by IS NULL AND approved_at IS NULL)),
  ADD CONSTRAINT ck_invitations_rejection CHECK(
    (status='REJECTED' AND rejected_by IS NOT NULL AND rejected_at IS NOT NULL AND rejection_reason IS NOT NULL AND CHAR_LENGTH(TRIM(rejection_reason))>0)
    OR (status<>'REJECTED' AND rejected_by IS NULL AND rejected_at IS NULL AND rejection_reason IS NULL)),
  ADD CONSTRAINT ck_invitations_cancellation CHECK(
    (status='CANCELLED' AND cancelled_by IS NOT NULL AND cancelled_at IS NOT NULL)
    OR (status<>'CANCELLED' AND cancelled_by IS NULL AND cancelled_at IS NULL)),
  ADD CONSTRAINT ck_invitations_token_disposal CHECK(status='PENDING' OR token_hash IS NULL);
ALTER TABLE products
  DROP FOREIGN KEY fk_products_category,
  MODIFY category_id BIGINT UNSIGNED NOT NULL,
  ADD CONSTRAINT fk_products_category_required FOREIGN KEY(category_id) REFERENCES product_categories(id) ON DELETE RESTRICT,
  ADD CONSTRAINT ck_products_positive_price CHECK(price_krw>0),
  ADD CONSTRAINT ck_products_period_validity CHECK(kind<>'PERIOD' OR valid_days IS NULL);

INSERT INTO permission_definitions(code,description,is_admin_only)
SELECT s.code,s.description,s.admin_only FROM (
  SELECT 'members:write' code,'회원 정보 변경' description,FALSE admin_only UNION ALL
  SELECT 'operations:write','운영 설정 및 상품 변경',FALSE UNION ALL
  SELECT 'payments:read','결제 내역 조회',FALSE UNION ALL
  SELECT 'payments:write','결제 처리',FALSE UNION ALL
  SELECT 'sales:read','매출 조회',FALSE UNION ALL
  SELECT 'facilities:write','시설 관리',FALSE UNION ALL
  SELECT 'notices:write','공지 관리',FALSE UNION ALL
  SELECT 'people:manage','직원 및 권한 관리',TRUE UNION ALL
  SELECT 'ai:read','AI 분석 조회',FALSE
) s WHERE NOT EXISTS(SELECT 1 FROM permission_definitions d WHERE d.code=s.code);
INSERT INTO permission_presets(name,role,is_system)
SELECT '일반 직원 기본','STAFF',TRUE WHERE NOT EXISTS(SELECT 1 FROM permission_presets WHERE name='일반 직원 기본' AND role='STAFF');
INSERT INTO permission_presets(name,role,is_system)
SELECT '지점 관리자 기본','MANAGER',TRUE WHERE NOT EXISTS(SELECT 1 FROM permission_presets WHERE name='지점 관리자 기본' AND role='MANAGER');
INSERT INTO permission_preset_grants(preset_id,permission_id)
SELECT p.id,d.id FROM permission_presets p CROSS JOIN permission_definitions d
WHERE p.name='지점 관리자 기본' AND p.role='MANAGER' AND d.code IN
  ('members:write','operations:write','payments:read','payments:write','sales:read','facilities:write','notices:write','people:manage','ai:read')
  AND NOT EXISTS(SELECT 1 FROM permission_preset_grants g WHERE g.preset_id=p.id AND g.permission_id=d.id);

-- BASELINE means current verified conditions, never fabricated past revisions.
INSERT INTO product_revisions(product_id,original_product_id,product_version,event_type,changed_at,request_id,reason,snapshot_json)
SELECT p.id,p.id,p.version,'BASELINE',UTC_TIMESTAMP(6),'migration:foundation:v3','Verified current V1 conditions; historical changes unknown',
 JSON_OBJECT('schemaVersion',1,'code',p.code,'name',p.name,'priceKrw',p.price_krw,
   'terms',CASE p.kind
     WHEN 'TIME' THEN JSON_OBJECT('kind','시간권','includedMinutes',p.included_minutes,'validDays',p.valid_days)
     WHEN 'PERIOD' THEN JSON_OBJECT('kind','기간권','periodDays',p.period_days)
     ELSE JSON_OBJECT('kind','정액권','creditKrw',p.credit_krw,'hourlyRateKrw',p.hourly_rate_krw,'validDays',p.valid_days) END,
   'status',IF(p.sale_status='ON_SALE','판매 중','판매 중지'),
   'categoryCode',c.code,'categoryName',c.name,
   'branchIds',(SELECT JSON_ARRAYAGG(CAST(b.branch_id AS CHAR)) FROM product_branches b WHERE b.product_id=p.id))
FROM products p JOIN product_categories c ON c.id=p.category_id;
INSERT INTO audit_events(entity_type,entity_id,action,reason,request_id,result)
VALUES('SCHEMA',NULL,'FOUNDATION_BASELINE','V3: legacy scope reviewed; unknown historical actors remain NULL','migration:foundation:v3','SUCCESS');
DROP TABLE foundation_legacy_review;
