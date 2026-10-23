"""Validate local foundation design references; no network or database access.

OpenAPI standards validation is separate:
  npx --yes --package @apidevtools/swagger-cli swagger-cli validate docs/design/foundation/openapi.json
This script checks project traceability/security invariants, not server behavior.
"""
import hashlib
import json
import re
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DESIGN = ROOT / 'docs/design/foundation'
spec = json.loads((DESIGN / 'openapi.json').read_text(encoding='utf-8'))
source = json.loads((DESIGN / 'requirements.json').read_text(encoding='utf-8'))
requirements = source['requirements']
ids = {row['id'] for row in requirements}
assert len(ids) == len(requirements) == source['requirementCount'] == 61
categories = Counter((r['major'], r['middle']) for r in requirements)
assert len(categories) == source['reviewedCategoryCount'] == 8
assert all(r['scope'] == '현재' for r in requirements)
assert spec['x-source']['sha256'] == source['sha256']
policies = spec['x-confirmed-policies']
assert set(policies) == {'Q' + str(n) for n in range(1, 13)}
assert all(p['status'] == 'confirmed' and p['confirmedBy'] == 'user'
           and p['confirmedAt'] == '2026-10-23' for p in policies.values())

def resolve(reference):
    assert reference.startswith('#/'), reference
    node = spec
    for part in reference[2:].split('/'):
        node = node[part.replace('~1', '/').replace('~0', '~')]
    return node

ref_count = 0
def walk(node):
    global ref_count
    if isinstance(node, dict):
        assert 'x-policy-assumptions' not in node, 'Confirmed policies must not remain assumptions.'
        if '$ref' in node:
            resolve(node['$ref'])
            ref_count += 1
        for value in node.values():
            walk(value)
    elif isinstance(node, list):
        for value in node:
            walk(value)
walk(spec)

operation_ids = set()
covered = set(spec['x-common-requirement-ids']) | set(spec['x-non-http-requirements'])
operation_count = 0
secret_names = {'password', 'passwordHash', 'password_hash', 'token_hash', 'tokenHash', 'token', 'sessionId', 'sessionSecret'}

def response_secret_fields(schema, seen=None):
    seen = set() if seen is None else seen
    if '$ref' in schema:
        path = schema['$ref']
        if path in seen:
            return set()
        return response_secret_fields(resolve(path), seen | {path})
    fields = set(schema.get('properties', {})) & secret_names
    for value in schema.get('properties', {}).values():
        fields |= response_secret_fields(value, seen)
    if 'items' in schema:
        fields |= response_secret_fields(schema['items'], seen)
    for key in ('oneOf', 'allOf', 'anyOf'):
        for child in schema.get(key, []):
            fields |= response_secret_fields(child, seen)
    return fields

for path, methods in spec['paths'].items():
    route_params = set(re.findall(r'\{(\w+)\}', path))
    for method, op in methods.items():
        assert method in {'get', 'post', 'put', 'patch', 'delete'}
        operation_count += 1
        assert op['operationId'] not in operation_ids, op['operationId']
        operation_ids.add(op['operationId'])
        assert op['x-permission'] and op['x-implementation-status'] == 'design-target'
        assert set(op.get('x-policy-decisions', [])) <= set(policies)
        reqs = set(op['x-requirement-ids'])
        assert reqs <= ids, reqs - ids
        covered |= reqs
        params = [resolve(p['$ref']) if '$ref' in p else p for p in op.get('parameters', [])]
        assert {p['name'] for p in params if p['in'] == 'path'} == route_params
        assert len({(p['in'], p['name']) for p in params}) == len(params)
        if method != 'get':
            csrf = next(p for p in params if p['name'] == 'X-CSRF-Token')
            assert csrf['in'] == 'header'
            assert csrf['required'] or op['operationId'] == 'logout'
        if op['operationId'].startswith('create'):
            assert any(p['name'] == 'Idempotency-Key' and p['required'] for p in params)
        for code, response in op['responses'].items():
            response = resolve(response['$ref']) if '$ref' in response else response
            for media in response.get('content', {}).values():
                fields = response_secret_fields(media['schema'])
                if op['operationId'] == 'getCsrf' and code == '200':
                    assert fields == {'token'}
                else:
                    assert not fields, (path, method, fields)
assert covered == ids, {'missing': sorted(ids-covered), 'unknown': sorted(covered-ids)}

operations = {op['operationId']: op for methods in spec['paths'].values() for op in methods.values()}
presets = {p['role']: p for p in spec['x-default-permission-presets']}
assert presets['staff']['permissions'] == []
assert set(presets['admin']['permissions']) == set(spec['components']['schemas']['PermissionCode']['enum'])
assert len(presets['admin']['permissions']) == 9
role_policy = operations['updateMembership']['x-target-role-policy']['manager']
assert role_policy['currentRoles'] == role_policy['requestedRoles'] == ['staff']
assert role_policy['allowSelfPermissionChange'] is False
assert role_policy['allowOwnerTarget'] is False
for oid in ('createInvitation', 'reissueInvitation', 'cancelInvitation', 'rejectInvitation', 'approveInvitation'):
    assert operations[oid]['x-manager-allowed-invitation-roles'] == ['staff']
delete = spec['paths']['/products/{id}']['delete']
assert delete['operationId'] == 'deleteProduct' and 'Q8' in delete['x-policy-decisions']
assert any(p.get('name') == 'version' and p.get('in') == 'query' and p.get('required')
           for p in delete['parameters'])
revision = spec['components']['schemas']['ProductRevision']['properties']
assert 'DELETED' in revision['changeType']['enum']
assert revision['after']['nullable'] is True

trace = (DESIGN / 'traceability.md').read_text(encoding='utf-8')
trace_ids = re.findall(r'^\| (FR-[A-Z]+-\d+) ·', trace, flags=re.M)
assert len(trace_ids) == len(set(trace_ids)) == 61
assert set(trace_ids) == ids
plan = (DESIGN / 'implementation-plan.md').read_text(encoding='utf-8')
issue_ids = set(re.findall(r'^### ([BFV]\d{2})\.', plan, flags=re.M))
assert len(issue_ids) == 16, issue_ids
assert set(re.findall(r'\b[BFV]\d{2}\b', trace)) <= issue_ids
for oid in re.findall(r'`([a-zA-Z]+)`', trace):
    assert oid in operation_ids, oid

for path in DESIGN.glob('*.md'):
    content = path.read_text(encoding='utf-8')
    assert not re.search('[\u3040-\u30ff]', content), path
    for target in re.findall(r'\]\(([^)]+)\)', content):
        if not target.startswith(('http:', 'https:', '#')):
            assert (path.parent / target.split('#')[0]).is_file(), (path.name, target)

workbook = ROOT.parent / source['source']
if workbook.exists():
    assert hashlib.sha256(workbook.read_bytes()).hexdigest() == source['sha256'], 'SRS changed: recapture and reconcile design.'

print(json.dumps({'requirements':len(ids),'categories':len(categories),'operations':operation_count,'schemas':len(spec['components']['schemas']),'resolvedReferences':ref_count,'issueDrafts':len(issue_ids),'confirmedPolicies':len(policies),'result':'passed'}, ensure_ascii=False))
