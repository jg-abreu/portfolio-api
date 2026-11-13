from datetime import datetime, timezone
from pathlib import Path
import hashlib
import json
from urllib.request import Request, urlopen

api = Path(__file__).resolve().parents[1]
tree_path = api/'target/dependency-tree-security.json'
raw = tree_path.read_bytes()
tree = json.loads(raw.decode('utf-8-sig'))
packages = {}
def visit(node):
    name = node['groupId']+':'+node['artifactId']
    key = (name, node['version'])
    packages.setdefault(key, set()).add(node.get('scope', ''))
    for child in node.get('children', []):
        visit(child)
for child in tree.get('children', []):
    visit(child)
assert packages, 'Empty dependency tree'
def query(payload):
    request=Request('https://api.osv.dev/v1/querybatch', data=json.dumps(payload).encode(), headers={'Content-Type':'application/json','User-Agent':'portfolio-local-dependency-review'})
    with urlopen(request, timeout=45) as response:
        return json.load(response)
keys = sorted(packages)
findings=[]
for offset in range(0,len(keys),50):
    batch=keys[offset:offset+50]
    pending=[(key, {'package':{'ecosystem':'Maven','name':key[0]},'version':key[1]}) for key in batch]
    while pending:
        result=query({'queries':[item[1] for item in pending]})
        assert isinstance(result.get('results'),list) and len(result['results'])==len(pending), 'Incomplete OSV result'
        following=[]
        for (key,payload),item in zip(pending,result['results']):
            if 'error' in item:
                raise RuntimeError('OSV query returned an error')
            for vuln in item.get('vulns',[]):
                findings.append({'package':key[0],'version':key[1],'scopes':sorted(packages[key]),'id':vuln['id'],'modified':vuln.get('modified')})
            if item.get('next_page_token'):
                following.append((key,{**payload,'page_token':item['next_page_token']}))
        pending=following
report={'checkedAt':datetime.now(timezone.utc).isoformat(),'treeSha256':hashlib.sha256(raw).hexdigest(),'packageVersions':len(keys),'findings':findings,'source':'https://api.osv.dev/v1/querybatch','limits':'Resolved Maven dependencies only; excludes build plugins, container/OS, reachability and source analysis.'}
(api/'target/dependency-osv-security.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'packageVersions':len(keys),'findingCount':len(findings),'findings':findings},indent=2))

raise SystemExit(1 if findings else 0)
