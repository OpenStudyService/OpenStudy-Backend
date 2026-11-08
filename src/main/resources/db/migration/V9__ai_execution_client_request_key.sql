-- #96: 같은 클라이언트 요청 키로 다시 제출한 질문은 새 실행을 만들지 않는다.
-- 키는 직원별로 유일하다. 키 없이 보낸 질문(NULL)은 여러 개일 수 있다.
ALTER TABLE `ai_executions`
  ADD COLUMN `client_request_key` varchar(100) DEFAULT NULL AFTER `request_ref`,
  ADD UNIQUE KEY `uk_ai_executions_client_key` (`employee_id`, `client_request_key`);
