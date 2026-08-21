import os
BASE_URL = os.environ.get("EVOCHORA_BASE_URL", "http://localhost:8081")
RUN_ID = os.environ.get("EVOCHORA_RUN_ID", "20260402-11564129-6fe1712c-1779-4256-bbfc-1601ec46b3b8")
DATA_DIR = os.environ.get("EVOCHORA_ANALYSIS_DIR", os.path.join(os.getcwd(), "analysis-data"))
PROTO_DIR = os.environ.get("EVOCHORA_PROTO_DIR", os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "src", "main", "proto"))
os.makedirs(DATA_DIR, exist_ok=True)
import json, sys, urllib.request
from concurrent.futures import ThreadPoolExecutor
R=RUN_ID
oid,lo,hi,step=int(sys.argv[1]),int(sys.argv[2]),int(sys.argv[3]),int(sys.argv[4])
def fetch(t):
    with urllib.request.urlopen(fBASE_URL+'/visualizer/api/organisms/{t}/{oid}?runId={R}',timeout=120) as r:
        d=json.load(r)
    x0,y0=d['staticInfo']['initialPosition']
    st=d['state']; li=st['instructions']['last']; ip=li['ipBeforeFetch']
    dp=st['dataPointers'][st['activeDpIndex']]
    return f"{t} E={st['energy']} S={st['entropyRegister']} {li['opcodeName']}@({ip[0]-x0},{ip[1]-y0}) dp=({dp[0]-x0},{dp[1]-y0}) depth={len(st['callStack'])} fail={li.get('failed')} dE={li.get('energyCost')} dS={li.get('entropyDelta')} {('FAILED:'+str(st.get('failureReason'))) if st.get('instructionFailed') else ''}"
ticks=list(range(lo,hi+1,step))
with ThreadPoolExecutor(6) as ex:
    for line in ex.map(fetch,ticks): print(line)
