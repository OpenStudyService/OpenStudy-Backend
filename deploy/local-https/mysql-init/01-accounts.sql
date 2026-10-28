-- Local stand-in for the RDS account split (docs/deployment/README.md#db-계정과-tls).
-- Throwaway local passwords only; real passwords live in SSM Parameter Store / Secrets Manager.
CREATE USER 'openstudy_migrator'@'%' IDENTIFIED BY 'local-migrator-password' REQUIRE SSL;
GRANT ALTER, CREATE, DROP, INDEX, REFERENCES, SELECT, INSERT, UPDATE, DELETE ON openstudy.* TO 'openstudy_migrator'@'%';

CREATE USER 'openstudy_app'@'%' IDENTIFIED BY 'local-app-password' REQUIRE SSL;
GRANT SELECT, INSERT, UPDATE, DELETE ON openstudy.* TO 'openstudy_app'@'%';
