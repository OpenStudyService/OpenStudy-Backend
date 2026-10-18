"""Static checks for OpenStudy v0.4. Does not replace a MySQL 8.4 integration test."""
from datetime import date, timedelta
from pathlib import Path
import re, runpy

here = Path(__file__).resolve().parent
builder = runpy.run_path(str(here / 'build_schema.py'))
tables = builder['D']
sql = (here / 'openstudy_schema_v0_4.sql').read_text(encoding='utf8')
logical = (here / 'openstudy_logical_erd.mmd').read_text(encoding='utf8')
physical = (here / 'openstudy_physical_erd.mmd').read_text(encoding='utf8')
space = (here / 'diagrams/04_space_physical.mmd').read_text(encoding='utf8')

assert len(tables) == 57
assert sum(len(v['fks']) for v in tables.values()) == 123
assert len(re.findall(r'^CREATE TABLE `', sql, re.M)) == 57
assert sum(len(v['checks']) for v in tables.values()) == 60
assert sql.count('FOREIGN KEY (') == 123
assert sql.count(' CHECK (') == 60

for name, spec in tables.items():
    assert f'CREATE TABLE `{name}` (' in sql, name
    assert f'  {name} {{' in physical, name
    for column in spec['cols']:
        colname = column.split()[0]
        assert f'`{colname}` ' in sql, (name, colname)
        assert f' {colname}' in physical, (name, colname)
    for local, other, remote, action in spec['fks']:
        assert other in tables and f'  {other} ||--o{{ {name} : ' in physical, (name,other)

for column in ['rate_per_4weeks_krw', 'charged_4week_terms', 'rate_per_4weeks_snapshot_krw']:
    assert column in sql and column in physical and column in space, column
for stale in ['monthly_rate_krw', 'charged_months', 'rate_per_month_snapshot_krw', 'plusMonths']:
    assert stale not in sql and stale not in physical and stale not in logical and stale not in space, stale
assert 'DATEDIFF(end_date, start_date) = charged_4week_terms * 28' in sql
assert 'amount_krw = charged_4week_terms * rate_per_4weeks_snapshot_krw' in sql
assert '4-week fee at purchase or extension' in logical

# Boundary cases in the application's intended date arithmetic.
scenarios = [
    (date(2027, 1, 31), 1, date(2027, 2, 28)),
    (date(2027, 1, 31), 2, date(2027, 3, 28)),
    (date(2028, 2, 10), 1, date(2028, 3, 9)),
    (date(2028, 2, 29), 3, date(2028, 5, 23)),
]
for start, units, expected in scenarios:
    assert start + timedelta(days=28 * units) == expected, (start,units)
# Extension continuity: the next charge starts at previous end, ending 28 days later.
first_end = date(2027, 1, 31) + timedelta(days=28)
assert first_end + timedelta(days=28) == date(2027, 3, 28)
print('OK: 57 tables, 123 FKs, 60 CHECK constraints; ERD columns/relations; fixed 28-day examples')
print('NOT TESTED: actual MySQL 8.4 DDL execution, concurrent transactions, Spring/JPA integration')
