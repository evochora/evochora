import os
BASE_URL = os.environ.get("EVOCHORA_BASE_URL", "http://localhost:8081")
RUN_ID = os.environ.get("EVOCHORA_RUN_ID", "20260402-11564129-6fe1712c-1779-4256-bbfc-1601ec46b3b8")
DATA_DIR = os.environ.get("EVOCHORA_ANALYSIS_DIR", os.path.join(os.getcwd(), "analysis-data"))
PROTO_DIR = os.environ.get("EVOCHORA_PROTO_DIR", os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "src", "main", "proto"))
os.makedirs(DATA_DIR, exist_ok=True)
import json, sys, urllib.request, pickle, os
from concurrent.futures import ThreadPoolExecutor
R=RUN_ID
lo,hi,out=int(sys.argv[1]),int(sys.argv[2]),sys.argv[3]
def fetch(t):
    for attempt in range(3):
        try:
            with urllib.request.urlopen(fBASE_URL+'/visualizer/api/organisms/{t}?runId={R}',timeout=120) as r:
                d=json.load(r)
            dead=[o for o in d['organisms'] if o['isDead']]
            alive=sum(1 for o in d['organisms'] if not o['isDead'])
            return t,dead,alive,d['totalOrganismCount']
        except Exception as e:
            err=e
    return t,None,None,None
ticks=list(range(lo,hi,100))
res={}
with ThreadPoolExecutor(6) as ex:
    for i,(t,dead,alive,tot) in enumerate(ex.map(fetch,ticks)):
        res[t]=(dead,alive,tot)
        if i%1000==0: print(i,len(ticks),flush=True)
pickle.dump(res,open(os.path.join(DATA_DIR,out),'wb'))
print('done', sum(1 for v in res.values() if v[0] is None),'failed')
