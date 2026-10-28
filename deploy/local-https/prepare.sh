#!/usr/bin/env bash
# Prepare throwaway local material for compose.yaml in .local/ (git-ignored). Never use it outside this machine.
# - local CA, proxy certificate (localhost) and MySQL certificate (hostname "mysql")
# - PKCS12 trust store with the local CA, for the app's sslMode=VERIFY_IDENTITY DB_URL
# - the MariaDB JDBC driver that Infra's Flyway runner uses, checked against Infra's pinned SHA-256
set -euo pipefail
# Git Bash on Windows would rewrite "/CN=..." and container paths as Windows paths.
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL="*"
cd "$(dirname "$0")"
mkdir -p .local/certs .local/drivers
cd .local

openssl req -x509 -newkey rsa:2048 -nodes -days 30 -subj "/CN=OpenStudy local CA" \
  -keyout certs/ca.key -out certs/ca.pem 2>/dev/null
issue() {
  local name=$1 subject=$2 san=$3
  openssl req -newkey rsa:2048 -nodes -subj "/CN=$subject" -keyout "certs/$name.key" -out "certs/$name.csr" 2>/dev/null
  printf 'subjectAltName=%s\n' "$san" > "certs/$name.ext"
  openssl x509 -req -in "certs/$name.csr" -CA certs/ca.pem -CAkey certs/ca.key -CAcreateserial -days 30 \
    -extfile "certs/$name.ext" -out "certs/$name.pem" 2>/dev/null
}
issue proxy localhost "DNS:localhost,IP:127.0.0.1"
issue mysql mysql "DNS:mysql"
# Bind-mounted into the mysql container (uid 999), so the throwaway keys must be readable there.
chmod 644 certs/*.key

rm -f certs/db-truststore.p12
docker run --rm -v "$(pwd)/certs:/certs" eclipse-temurin:21-jre-noble \
  keytool -importcert -noprompt -alias local-ca -file /certs/ca.pem \
  -keystore /certs/db-truststore.p12 -storetype PKCS12 -storepass local-public-ca >/dev/null
chmod 644 certs/db-truststore.p12

driver=drivers/mariadb-java-client-3.5.9.jar
curl -fsSL https://repo.maven.apache.org/maven2/org/mariadb/jdbc/mariadb-java-client/3.5.9/mariadb-java-client-3.5.9.jar -o "$driver"
echo "11e3bb5bbf8ef0e806ae4d6c5d5033fedf7262cc777f0190bde8a2f3c8e6bd8d  $driver" | sha256sum --check --status
echo "Prepared $(pwd)"
