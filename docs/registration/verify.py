"""Empirical handoff checks; run from the project root after Maven finishes."""
import hashlib,json,re,subprocess,xml.etree.ElementTree as ET
from pathlib import Path
root=Path.cwd(); out=root/'docs/registration'
reports=[]
for p in sorted((root/'target/surefire-reports').glob('TEST-*.xml')):
    r=ET.parse(p).getroot()
    reports.append({'suite':r.get('name'),**{k:int(r.get(k,'0')) for k in ('tests','failures','errors','skipped')}})
totals={k:sum(r[k] for r in reports) for k in ('tests','failures','errors','skipped')}
browser=json.loads((out/'browser-results.json').read_text(encoding='utf-8'))
schema=(root/'schema.sql').read_text(encoding='utf-8'); docker_schema=(root/'docker/initdb/01_schema.sql').read_text(encoding='utf-8')
head=subprocess.check_output(['git','-c',f'safe.directory={root.as_posix()}','show','HEAD:schema.sql'],cwd=root)
checks={
 'test_failures_zero':totals['failures']==0 and totals['errors']==0,
 '22_tables':len(re.findall(r'CREATE TABLE ',schema))==22,
 'schema_unchanged':schema.replace('\r\n','\n')==head.decode('utf-8').replace('\r\n','\n'),
 'docker_schema_matches':schema.replace('\r\n','\n')==docker_schema.replace('\r\n','\n'),
 'one_role_fk':bool(re.search(r'role_id BIGINT NOT NULL',schema)),
 'email_phone_identity_unique':all(c in schema for c in ['uk_users_email','uk_users_phone','uk_users_provider']),
 'customer_seed_exists':"'ROLE_CUSTOMER'" in (root/'docker/initdb/02_seed_roles.sql').read_text(encoding='utf-8'),
 'jar_exists':(root/'target/AureliaBooks-0.0.1-SNAPSHOT.jar').is_file(),
 'responsive_widths':{r['width'] for r in browser['results']}=={375,768,1440},
 'no_horizontal_overflow':all(not r['overflow'] for r in browser['results']),
 'button_matches_brand':all(r['buttonColor']=='rgb(217, 74, 38)' for r in browser['results']),
 'no_browser_page_errors':not browser['errors']
}
result={'totals':totals,'suites':reports,'checks':checks,
 'rds_sha256':hashlib.sha256((root.parents[1]/'Document/02_RDS Document.docx').read_bytes()).hexdigest(),
 'limitations':['MySQL persistence and concurrent race NOT_RUN: daemon unavailable; port 3306 closed',
 'Google Cloud real client/token round trip NOT_RUN: no live credential flow exercised',
 'Browser checks use Thymeleaf-rendered HTML from MockMvc, not a live database-backed server',
 'Existing contextLoads database test remains disabled']}
(out/'verification-results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'totals':totals,'checks':checks},ensure_ascii=False))
raise SystemExit(0 if all(checks.values()) else 1)
