-- db_setup_local.sql
-- 로컬 개발 환경 초기 설정(DB 및 계정 생성)
-- root 계정으로 실행

-- STEP 1. 프로젝트 데이터베이스 생성
CREATE DATABASE openstudy
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

-- STEP 2. 프로젝트 계정 생성
-- 비밀번호는 직접 설정
CREATE USER 'openstudy_app'@'localhost'
IDENTIFIED BY 'CHANGE_ME';

-- STEP 3. 프로젝트 DB 권한 부여
GRANT ALL PRIVILEGES
ON openstudy.*
TO 'openstudy_app'@'localhost';

-- STEP 4. 생성 결과 확인
SHOW DATABASES;

SHOW GRANTS FOR 'openstudy_app'@'localhost';
