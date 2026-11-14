from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
import hashlib, http.client, json, math, os, secrets, shutil, socket, statistics, subprocess, threading, time, uuid

api=Path(__file__).resolve().parents[1]
docker=shutil.which('docker')
java_home=os.environ.get('JAVA_HOME')
java=str(Path(java_home)/'bin'/('java.exe' if os.name=='nt' else 'java')) if java_home else shutil.which('java')
jar=api/'target/portfolio-api-0.0.1-SNAPSHOT.jar'
assert docker and java and jar.is_file(), 'Requires Docker, Java 21 and packaged API'
name='portfolio-v2-review-'+uuid.uuid4().hex[:12]
processes=[]
logs=[]
proxy=None
proxy_thread=None
executor=None
container_started=False
affinity={}
routes=[0,0]
lock=threading.Lock()
round_robin=0
json_headers={'Content-Type':'application/json','User-Agent':'Portfolio local capacity review'}

def command(*arguments):
    return subprocess.check_output(arguments,text=True,stderr=subprocess.PIPE).strip()

def port():
    with socket.socket() as s:
        s.bind(('127.0.0.1',0))
        return s.getsockname()[1]

def fetch(url,method='GET',headers=None,body=None):
    request=Request(url,data=body,method=method,headers=headers or {})
    try:
        response=urlopen(request,timeout=20)
    except HTTPError as error:
        response=error
    with response:
        data=response.read()
        return response.status, dict(response.headers), json.loads(data) if data else {}

def wait_ready(server_port,process):
    deadline=time.monotonic()+75
    while time.monotonic()<deadline:
        assert process.poll() is None, 'Review API exited; inspect ignored local log'
        try:
            if fetch(f'http://127.0.0.1:{server_port}/actuator/health')[2].get('status')=='UP': return
        except (URLError,TimeoutError): pass
        time.sleep(.25)
    raise RuntimeError('Review API readiness timed out')

class Proxy(BaseHTTPRequestHandler):
    def forward(self):
        global round_robin
        cookie=SimpleCookie(self.headers.get('Cookie',''))
        sid=cookie['JSESSIONID'].value if 'JSESSIONID' in cookie else None
        with lock:
            live=[i for i,p in enumerate(processes) if p.poll() is None]
            if not live:
                self.send_error(503); return
            index=affinity.get(sid)
            if self.headers.get('X-Review-Backend') in ('0','1'):
                index=int(self.headers['X-Review-Backend'])
            if index not in live:
                index=live[round_robin%len(live)]; round_robin+=1
            routes[index]+=1
        body=self.rfile.read(int(self.headers.get('Content-Length','0')))
        headers={k:v for k,v in self.headers.items() if k.lower() not in ('host','connection','content-length','x-review-backend','x-review-ip','x-real-ip','cf-connecting-ip')}
        headers['X-Real-IP']='203.0.113.10'
        headers['CF-Connecting-IP']=self.headers.get('X-Review-IP','192.0.2.1')
        connection=http.client.HTTPConnection('127.0.0.1',ports[index],timeout=20)
        try:
            connection.request(self.command,self.path,body=body,headers=headers)
            response=connection.getresponse()
            data=response.read()
            self.send_response(response.status)
            for key,value in response.getheaders():
                if key.lower() in ('transfer-encoding','connection','content-length'): continue
                self.send_header(key,value)
                if key.lower()=='set-cookie':
                    assigned=SimpleCookie(value)
                    if 'JSESSIONID' in assigned:
                        with lock: affinity[assigned['JSESSIONID'].value]=index
            self.send_header('Content-Length',str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        finally: connection.close()
    do_GET=forward
    do_POST=forward
    def log_message(self,*args): pass

def session(ip):
    status,headers,csrf=fetch(origin+'/api/auth/csrf',headers={'X-Review-IP':ip})
    assert status==200 and csrf['headerName']=='X-CSRF-TOKEN'
    cookies=SimpleCookie(headers.get('Set-Cookie',''))
    assert 'JSESSIONID' in cookies
    return {'ip':ip,'cookie':'JSESSIONID='+cookies['JSESSIONID'].value,'token':csrf['token'],'node':affinity[cookies['JSESSIONID'].value]}

def post(client,forced=None):
    headers={**json_headers,'X-Review-IP':client['ip'],'Cookie':client['cookie'],'X-CSRF-TOKEN':client['token']}
    if forced is not None: headers['X-Review-Backend']=str(forced)
    start=time.monotonic()
    status,returned,value=fetch(origin+'/api/visits','POST',headers,b'')
    return status,value,(time.monotonic()-start)*1000,returned.get('Cache-Control','')

try:
    command(docker,'run','--rm','-d','--name',name,'-e','POSTGRES_DB=portfolio_review','-e','POSTGRES_USER=portfolio','-e','POSTGRES_PASSWORD=synthetic-review-only','-p','127.0.0.1::5432','postgres:18-alpine')
    container_started=True
    database_port=command(docker,'port',name,'5432/tcp').split(':')[-1]
    ports=[port(),port()]
    while ports[0]==ports[1]: ports[1]=port()
    secret=secrets.token_urlsafe(48)
    for i,server_port in enumerate(ports):
        env=os.environ.copy()
        for key in ('SPRING_APPLICATION_JSON','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS'): env.pop(key,None)
        env.update({'SERVER_PORT':str(server_port),'SERVER_ADDRESS':'127.0.0.1','SPRING_PROFILES_ACTIVE':'local',
          'SPRING_DATASOURCE_URL':f'jdbc:postgresql://127.0.0.1:{database_port}/portfolio_review',
          'SPRING_DATASOURCE_USERNAME':'portfolio','SPRING_DATASOURCE_PASSWORD':'synthetic-review-only',
          'SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE':'4','SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE':'1',
          'SPRING_DOCKER_COMPOSE_ENABLED':'false','SPRING_JPA_HIBERNATE_DDL_AUTO':'validate','SPRING_JPA_OPEN_IN_VIEW':'false',
          'SERVER_TOMCAT_THREADS_MAX':'30','SERVER_TOMCAT_THREADS_MIN_SPARE':'2','SERVER_SERVLET_SESSION_COOKIE_SECURE':'false',
          'GITHUB_CLIENT_ID':'synthetic-unused','GITHUB_CLIENT_SECRET':'synthetic-unused','GITHUB_REDIRECT_URI':'http://localhost/login/oauth2/code/github',
          'GEMINI_API_KEY':'','GOOGLE_API_KEY':'','GOOGLE_BOOKS_API_KEY':'','PORTFOLIO_PROFILE_CARD_ENABLED':'false','PROFILE_CARD_GITHUB_TOKEN':'',
          'PORTFOLIO_CONTACT_SUBMISSION_ENABLED':'false','PORTFOLIO_CONTACT_MAIL_ENABLED':'false','PORTFOLIO_CONTACT_RETENTION_ENABLED':'false',
          'PORTFOLIO_PUBLICATION_ENABLED':'false','PUBLICATION_GITHUB_TOKEN':'','PORTFOLIO_VISITS_ENABLED':'true',
          'PORTFOLIO_VISITS_HMAC_KEY':secret,'PORTFOLIO_VISITS_IP_SOURCE':'CLOUDFLARE_PROXY',
          'PORTFOLIO_VISITS_TRUSTED_PROXIES':'127.0.0.1/32','PORTFOLIO_VISITS_CLOUDFLARE_CIDRS':'203.0.113.10/32',
          'SERVER_FORWARD_HEADERS_STRATEGY':'NONE','LOGGING_LEVEL_ROOT':'ERROR'})
        log=(api/f'target/two-instance-node-{i}.log').open('wb'); logs.append(log)
        p=subprocess.Popen([java,'-Xms32m','-Xmx256m','-jar',str(jar)],cwd=api,env=env,stdout=log,stderr=log,creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
        processes.append(p)
        wait_ready(server_port,p)
        print(f'Isolated node {i} healthy; PID={p.pid}',flush=True)
    proxy=ThreadingHTTPServer(('127.0.0.1',0),Proxy)
    origin=f'http://127.0.0.1:{proxy.server_address[1]}'
    proxy_thread=threading.Thread(target=proxy.serve_forever,daemon=True); proxy_thread.start()
    a=session('192.0.2.1')
    assert post(a,1-a['node'])[0]==403, 'Cross-node CSRF unexpectedly accepted'
    first=post(a)
    assert first[0]==200 and first[1]['counted'] and first[1]['total']==1
    b=session('192.0.2.1')
    assert b['node']!=a['node'], 'Expected distinct round-robin nodes'
    repeated=post(b)
    assert repeated[0]==200 and not repeated[1]['counted'] and repeated[1]['total']==1
    clients=[session(f'192.0.2.{i}') for i in range(2,18)]
    executor=ThreadPoolExecutor(max_workers=8)
    start=time.monotonic()
    futures=[executor.submit(post,client) for client in clients for _ in range(6)]
    receipts=[f.result() for f in futures]
    elapsed=time.monotonic()-start
    assert len(receipts)==96 and all(r[0]==200 and 'no-store' in r[3] for r in receipts)
    assert sum(1 for r in receipts if r[1]['counted'])==16, 'Cross-instance duplicate/lost counts'
    totals=[fetch(f'http://127.0.0.1:{p}/api/visits')[2]['total'] for p in ports]
    assert totals==[17,17]
    stored=command(docker,'exec',name,'psql','-U','portfolio','-d','portfolio_review','-tAc','SELECT count(*),min(octet_length(ip_hash)),max(octet_length(ip_hash)) FROM visits;')
    assert stored=='17|32|32', 'Unexpected persisted identities'
    memory=None
    if os.name=='nt':
        powershell=shutil.which('powershell')
        if powershell:
            memory=json.loads(command(powershell,'-NoProfile','-Command',f'Get-Process -Id {processes[0].pid},{processes[1].pid} | Select-Object Id,WorkingSet64,PrivateMemorySize64 | ConvertTo-Json -Compress'))
    processes[a['node']].terminate()
    processes[a['node']].wait(timeout=20)
    assert post(a)[0]==403, 'Old session must not survive on another node'
    recovered=session(a['ip'])
    after_failover=post(recovered)
    assert after_failover[0]==200 and not after_failover[1]['counted'] and after_failover[1]['total']==17
    assert fetch(origin+'/api/admin/session')[0]==401
    latencies=sorted(r[2] for r in receipts)
    report={'checkedAt':datetime.now(timezone.utc).isoformat(),'jarSha256':hashlib.sha256(jar.read_bytes()).hexdigest(),
      'concurrentPosts':96,'workers':8,'newIdentities':16,'newlyCounted':16,'finalTotalBothNodes':totals,
      'loadElapsedSeconds':round(elapsed,3),'requestsPerSecond':round(96/elapsed,2),
      'latencyMs':{'p50':round(statistics.median(latencies),2),'p95':round(latencies[math.ceil(.95*len(latencies))-1],2),'max':round(max(latencies),2)},
      'nodeRoutedRequests':routes,'localJvmMemory':memory,'crossNodeCsrfStatus':403,'afterFailoverOldCsrfStatus':403,
      'afterFailoverReacquiredCsrfTotal':17,'anonymousAdminStatus':401,
      'limits':'Local synthetic routing and documentation-only proxy CIDRs; not real Cloudflare/nginx or VM capacity. Sessions are in-memory; affinity helps normal routing but failover requires a new session/CSRF and admin reauthentication.'}
    (api/'target/two-instance-review.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(report,indent=2),flush=True)
finally:
    if proxy:
        proxy.shutdown(); proxy.server_close()
    if proxy_thread: proxy_thread.join(timeout=5)
    if executor: executor.shutdown(wait=True,cancel_futures=True)
    for p in processes:
        if p.poll() is None:
            p.terminate()
            try: p.wait(timeout=20)
            except subprocess.TimeoutExpired: p.kill(); p.wait(timeout=10)
    for log in logs: log.close()
    if container_started:
        subprocess.run([docker,'stop','--time','5',name],check=True,stdout=subprocess.DEVNULL)
    print('Owned review processes/proxy/container cleaned up; habitual services untouched',flush=True)
