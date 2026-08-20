import os
BASE_URL = os.environ.get("EVOCHORA_BASE_URL", "http://localhost:8081")
RUN_ID = os.environ.get("EVOCHORA_RUN_ID", "20260402-11564129-6fe1712c-1779-4256-bbfc-1601ec46b3b8")
DATA_DIR = os.environ.get("EVOCHORA_ANALYSIS_DIR", os.path.join(os.getcwd(), "analysis-data"))
PROTO_DIR = os.environ.get("EVOCHORA_PROTO_DIR", os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "src", "main", "proto"))
os.makedirs(DATA_DIR, exist_ok=True)
import json, sys, urllib.request
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
R=RUN_ID
oid,lo,hi,step=int(sys.argv[1]),int(sys.argv[2]),int(sys.argv[3]),int(sys.argv[4])
def fetch(t):
    try:
        with urllib.request.urlopen(fBASE_URL+'/visualizer/api/organisms/{t}/{oid}?runId={R}',timeout=120) as r:
            d=json.load(r)
        x0,y0=d['staticInfo']['initialPosition']
        li=d['state']['instructions']['last']
        ip=li['ipBeforeFetch']
        cs=d['state']['callStack']
        return (t,d['state']['energy'],d['state']['entropyRegister'],li['opcodeName'],(ip[0]-x0,ip[1]-y0),len(cs),li.get('failed'))
    except Exception as e:
        return (t,'ERR',str(e)[:80],None,None,None,None)
ticks=list(range(lo,hi+1,step))
with ThreadPoolExecutor(6) as ex: res=list(ex.map(fetch,ticks))
rows=Counter(); ops=Counter(); fails=0
for t,e,s,op,ip,depth,failed in res:
    if e=='ERR': print('ERR',t,s); continue
    rows[(ip[1], 'depth%d'%depth)]+=1; ops[op]+=1
    if failed: fails+=1
print('samples',len(res),'fails',fails)
print('rows (y, callstack depth):', sorted(rows.items(), key=lambda kv:-kv[1])[:15])
print('ops:', ops.most_common(15))
