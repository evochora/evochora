import os
BASE_URL = os.environ.get("EVOCHORA_BASE_URL", "http://localhost:8081")
RUN_ID = os.environ.get("EVOCHORA_RUN_ID", "20260402-11564129-6fe1712c-1779-4256-bbfc-1601ec46b3b8")
DATA_DIR = os.environ.get("EVOCHORA_ANALYSIS_DIR", os.path.join(os.getcwd(), "analysis-data"))
PROTO_DIR = os.environ.get("EVOCHORA_PROTO_DIR", os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "src", "main", "proto"))
os.makedirs(DATA_DIR, exist_ok=True)
import json, sys, urllib.request, pickle
from concurrent.futures import ThreadPoolExecutor
R=RUN_ID
oid,lo,hi,step=int(sys.argv[1]),int(sys.argv[2]),int(sys.argv[3]),int(sys.argv[4])
def fetch(t):
    try:
        with urllib.request.urlopen(fBASE_URL+'/visualizer/api/organisms/{t}?runId={R}',timeout=120) as r:
            d=json.load(r)
        for o in d['organisms']:
            if o['organismId']==oid: return (t,o['energy'],o['entropyRegister'],o['isDead'])
        return (t,None,None,None)
    except Exception as e:
        return (t,'ERR',str(e),None)
ticks=list(range((lo//100)*100,hi+1,step))
with ThreadPoolExecutor(6) as ex: res=list(ex.map(fetch,ticks))
pickle.dump(res,open(os.path.join(DATA_DIR,f'traj_{oid}.pkl'),'wb'))
# compact print: every Nth
for r in res:
    if r[1] is None: continue
    print(r)
