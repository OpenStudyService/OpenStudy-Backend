"""Rebuild the full schema SQL and the Mermaid ERD from a disposable MySQL that Flyway migrated.

Usage: python scripts/export_schema_snapshot.py [--metadata <json path>]

What it does (Docker required, no network access beyond local Docker, no application data):
  1. Starts a throwaway mysql:8.4.11 and applies src/main/resources/db/migration with flyway/flyway:11.20.2.
  2. Writes docs/db/schema-full.sql (tables in FK order, views, reference seeds, real Flyway history)
     and docs/db/erd.mmd (every table column and physical FK; Flyway history and views excluded).
  3. Restores schema-full.sql into a second empty schema and compares columns, indexes, FKs, CHECKs
     and views with the migrated schema. Fails if anything differs.
  4. Removes the containers and network.
The root password is a random value that exists only for the lifetime of the throwaway container.
"""
import argparse
import json
import re
import secrets
import subprocess
import sys
import time
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATIONS = ROOT / 'src/main/resources/db/migration'
SCHEMA_OUT = ROOT / 'docs/db/schema-full.sql'
ERD_OUT = ROOT / 'docs/db/erd.mmd'
MYSQL_IMAGE = 'mysql:8.4.11'
FLYWAY_IMAGE = 'flyway/flyway:11.20.2'
TAG = 'openstudy-schema-snapshot-' + secrets.token_hex(3)
NET, DB_CT = TAG + '-net', TAG + '-db'
PASSWORD = secrets.token_urlsafe(18)
NUMERIC = {'tinyint', 'smallint', 'mediumint', 'int', 'bigint', 'decimal', 'float', 'double', 'bit'}


def run(*args, check=True, capture=True):
    result = subprocess.run(list(args), capture_output=capture, text=True, encoding='utf-8')
    if check and result.returncode != 0:
        raise RuntimeError(f'{args[:4]} failed: {result.stderr.strip()}')
    return result.stdout if capture else ''


def unescape(value):
    if value == 'NULL':
        return None
    return re.sub(r'\\(.)', lambda m: {'n': '\n', 't': '\t', '0': '\0', '\\': '\\'}.get(m.group(1), m.group(1)), value)


def query(db, sql):
    out = run('docker', 'exec', '-e', f'MYSQL_PWD={PASSWORD}', DB_CT, 'mysql', '-uroot', '-N', '-B',
              '--default-character-set=utf8mb4', db, '-e', sql)
    return [[unescape(v) for v in line.split('\t')] for line in out.splitlines() if line]


def sql_literal(value, data_type):
    if value is None:
        return 'NULL'
    if data_type in NUMERIC:
        return value
    return "'" + value.replace('\\', '\\\\').replace("'", "''") + "'"


def start_database():
    run('docker', 'network', 'create', NET)
    run('docker', 'run', '-d', '--name', DB_CT, '--network', NET, '-e', f'MYSQL_ROOT_PASSWORD={PASSWORD}',
        '-e', 'MYSQL_DATABASE=openstudy', MYSQL_IMAGE,
        '--character-set-server=utf8mb4', '--collation-server=utf8mb4_0900_ai_ci', '--default-time-zone=+00:00')
    for _ in range(90):
        probe = subprocess.run(['docker', 'exec', '-e', f'MYSQL_PWD={PASSWORD}', DB_CT, 'mysql', '-uroot',
                                '-h127.0.0.1', '-e', 'SELECT 1'], capture_output=True)
        if probe.returncode == 0:
            return
        time.sleep(2)
    raise RuntimeError('MySQL did not become ready')


def migrate():
    run('docker', 'run', '--rm', '--network', NET, '-v', f'{MIGRATIONS.as_posix()}:/flyway/sql:ro', FLYWAY_IMAGE,
        f'-url=jdbc:mysql://{DB_CT}:3306/openstudy?allowPublicKeyRetrieval=true&useSSL=false',
        '-user=root', f'-password={PASSWORD}', '-locations=filesystem:/flyway/sql', 'migrate')


def fk_rows(db):
    return query(db, f"""SELECT k.TABLE_NAME, k.CONSTRAINT_NAME, k.COLUMN_NAME, k.REFERENCED_TABLE_NAME,
        k.REFERENCED_COLUMN_NAME, k.ORDINAL_POSITION FROM information_schema.KEY_COLUMN_USAGE k
        WHERE k.TABLE_SCHEMA='{db}' AND k.REFERENCED_TABLE_NAME IS NOT NULL
        ORDER BY k.TABLE_NAME, k.CONSTRAINT_NAME, k.ORDINAL_POSITION""")


def table_order(tables, fks):
    parents = defaultdict(set)
    for child, _, _, parent, _, _ in fks:
        if parent != child:
            parents[child].add(parent)
    # Alphabetical passes; a table is emitted as soon as all of its parents are emitted (same order as earlier exports).
    ordered, remaining = [], set(tables)
    while remaining:
        progressed = False
        for t in sorted(remaining, key=lambda s: s.encode()):
            if parents[t] <= set(ordered):
                ordered.append(t)
                remaining.discard(t)
                progressed = True
        if not progressed:
            raise RuntimeError(f'FK cycle among {sorted(remaining)}')
    return ordered


def metadata(db):
    tables = [r[0] for r in query(db, f"SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='{db}' AND TABLE_TYPE='BASE TABLE'")]
    views = [r[0] for r in query(db, f"SELECT TABLE_NAME FROM information_schema.VIEWS WHERE TABLE_SCHEMA='{db}' ORDER BY TABLE_NAME")]
    columns = query(db, f"""SELECT TABLE_NAME, COLUMN_NAME, ORDINAL_POSITION, COLUMN_TYPE, IS_NULLABLE, COLUMN_KEY,
        COLUMN_DEFAULT, EXTRA, GENERATION_EXPRESSION, COLUMN_COMMENT, DATA_TYPE FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA='{db}' ORDER BY TABLE_NAME, ORDINAL_POSITION""")
    indexes = query(db, f"""SELECT TABLE_NAME, INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME, EXPRESSION
        FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='{db}' ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX""")
    checks = query(db, f"""SELECT tc.TABLE_NAME, cc.CONSTRAINT_NAME, cc.CHECK_CLAUSE FROM information_schema.CHECK_CONSTRAINTS cc
        JOIN information_schema.TABLE_CONSTRAINTS tc ON tc.CONSTRAINT_SCHEMA=cc.CONSTRAINT_SCHEMA
        AND tc.CONSTRAINT_NAME=cc.CONSTRAINT_NAME WHERE cc.CONSTRAINT_SCHEMA='{db}' ORDER BY 1, 2""")
    comments = query(db, f"SELECT TABLE_NAME, TABLE_COMMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA='{db}' AND TABLE_TYPE='BASE TABLE' ORDER BY 1")
    view_defs = query(db, f"SELECT TABLE_NAME, VIEW_DEFINITION FROM information_schema.VIEWS WHERE TABLE_SCHEMA='{db}' ORDER BY 1")
    return {'tables': sorted(tables, key=lambda s: s.encode()), 'views': views, 'columns': columns,
            'indexes': indexes, 'foreignKeys': fk_rows(db), 'checks': checks, 'comments': comments,
            'viewDefinitions': [[n, d.replace(f'`{db}`.', '')] for n, d in view_defs]}


def build_schema_sql(db, meta, versions):
    order = table_order(meta['tables'], meta['foreignKeys'])
    parts = [f'-- OpenStudy full schema / MySQL 8.4 / Flyway V1~V{versions[-1]}',
             '-- EMPTY database only. Includes real Flyway history, reference seeds, no business credentials.',
             '-- Generated by scripts/export_schema_snapshot.py from a database migrated by Flyway.',
             'SET NAMES utf8mb4;', "SET time_zone = '+00:00';"]
    for table in order:
        ddl = query(db, f'SHOW CREATE TABLE `{table}`')[0][1]
        parts.append(ddl + ';\n')
    for view in meta['views']:
        ddl = query(db, f'SHOW CREATE VIEW `{view}`')[0][1]
        parts.append(re.sub(r' DEFINER=`[^`]*`@`[^`]*`', '', ddl) + ';\n')
    types = defaultdict(dict)
    for t, c, _, _, _, _, _, _, _, _, dt in meta['columns']:
        types[t][c] = dt
    seeded = [t for t in order if t != 'flyway_schema_history'] + ['flyway_schema_history']
    for table in seeded:
        cols = [c for c in types[table]]
        generated = {r[1] for r in meta['columns'] if r[0] == table and re.search(r'(VIRTUAL|STORED) GENERATED', r[7] or '')}
        cols = [c for c in cols if c not in generated]
        select = ', '.join(f"DATE_FORMAT(`{c}`, '%Y-%m-%d %H:%i:%s.%f')" if types[table][c] in ('datetime', 'timestamp')
                           else f'`{c}`' for c in cols)
        pk = 'installed_rank' if table == 'flyway_schema_history' else cols[0]
        for row in query(db, f'SELECT {select} FROM `{table}` ORDER BY `{pk}`' if pk in cols else f'SELECT {select} FROM `{table}`'):
            values = ','.join(sql_literal(v, types[table][c]) for c, v in zip(cols, row))
            parts.append(f"INSERT INTO `{table}` ({','.join(f'`{c}`' for c in cols)}) VALUES ({values});")
    return '\n'.join(parts) + '\n'


def build_erd(meta, versions):
    fk_cols = {(r[0], r[2]) for r in meta['foreignKeys']}
    lines = [f'%% OpenStudy full schema / MySQL 8.4 / Flyway V1~V{versions[-1]} / generated from migrated DB metadata',
             '%% All business/session columns and physical foreign keys; Flyway history and views excluded.',
             '%% Regenerate with: python scripts/export_schema_snapshot.py', 'erDiagram']
    nullable = {}
    by_table = defaultdict(list)
    for t, c, _, ctype, isnull, key, *_ in meta['columns']:
        by_table[t].append((c, ctype, key))
        nullable[(t, c)] = isnull == 'YES'
    for table in meta['tables']:
        if table == 'flyway_schema_history':
            continue
        lines.append(f'  {table} {{')
        for c, ctype, key in by_table[table]:
            flags = (['PK'] if key == 'PRI' else []) + (['UK'] if key == 'UNI' else []) + (['FK'] if (table, c) in fk_cols else [])
            lines.append(f"    {re.sub(r'[^a-zA-Z0-9_]', '_', ctype).rstrip('_')} {c}" + (' ' + ','.join(flags) if flags else ''))
        lines.append('  }')
    grouped = defaultdict(list)
    for child, name, col, parent, _, _ in meta['foreignKeys']:
        grouped[(child, name, parent)].append(col)
    for (child, name, parent), cols in sorted(grouped.items(), key=lambda kv: (kv[0][0].encode(), kv[0][1].encode())):
        left = '|o' if any(nullable[(child, c)] for c in cols) else '||'
        lines.append(f'  {parent} {left}--o{{ {child} : "{name}: {",".join(cols)}"')
    return '\n'.join(lines) + '\n'


def comparable(meta):
    copy = dict(meta)
    copy['columns'] = [r[:10] for r in meta['columns']]
    return json.dumps(copy, ensure_ascii=False, sort_keys=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--metadata', help='also write the migrated schema metadata as JSON to this path')
    args = parser.parse_args()
    versions = sorted(int(re.match(r'V(\d+)__', p.name).group(1)) for p in MIGRATIONS.glob('V*__*.sql'))
    try:
        start_database()
        migrate()
        meta = metadata('openstudy')
        schema_sql = build_schema_sql('openstudy', meta, versions)
        SCHEMA_OUT.write_text(schema_sql, encoding='utf-8', newline='\n')
        ERD_OUT.write_text(build_erd(meta, versions), encoding='utf-8', newline='\n')
        query('openstudy', 'CREATE DATABASE restore_check CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci')
        restore = subprocess.run(['docker', 'exec', '-i', '-e', f'MYSQL_PWD={PASSWORD}', DB_CT, 'mysql', '-uroot',
                                  '--default-character-set=utf8mb4', 'restore_check'],
                                 input=schema_sql, capture_output=True, text=True, encoding='utf-8')
        if restore.returncode != 0:
            raise RuntimeError('restore failed: ' + restore.stderr.strip())
        restored = metadata('restore_check')
        if comparable(restored) != comparable(meta):
            raise RuntimeError('restored schema differs from migrated schema')
        count_sql = ' UNION ALL '.join(f"SELECT '{t}', COUNT(*) FROM `{t}`" for t in meta['tables'])
        if query('openstudy', count_sql) != query('restore_check', count_sql):
            raise RuntimeError('restored seed rows differ from migrated rows')
        seeded_tables = {t: int(n) for t, n in query('openstudy', count_sql) if int(n)}
        history = query('restore_check', 'SELECT COUNT(*), MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1')[0]
        summary = {'mysqlImage': MYSQL_IMAGE, 'flywayImage': FLYWAY_IMAGE, 'latestVersion': versions[-1],
                   'tables': len(meta['tables']), 'views': len(meta['views']), 'columns': len(meta['columns']),
                   'foreignKeys': len({(r[0], r[1]) for r in meta['foreignKeys']}),
                   'checks': len(meta['checks']), 'indexes': len({(r[0], r[1]) for r in meta['indexes']}),
                   'seededTables': seeded_tables, 'restoredFlywayRows': int(history[0]), 'restoredMaxVersion': int(history[1])}
        if args.metadata:
            Path(args.metadata).write_text(json.dumps({'summary': summary, **meta}, ensure_ascii=False, indent=1), encoding='utf-8')
        print(json.dumps(summary, ensure_ascii=False))
    finally:
        subprocess.run(['docker', 'rm', '-f', DB_CT], capture_output=True)
        subprocess.run(['docker', 'network', 'rm', NET], capture_output=True)


if __name__ == '__main__':
    sys.exit(main())
