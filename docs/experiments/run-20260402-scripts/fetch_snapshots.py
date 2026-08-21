import os, sys, json, urllib.request
from concurrent.futures import ThreadPoolExecutor
BASE_URL = os.environ.get("EVOCHORA_BASE_URL", "http://localhost:8081")
RUN_ID = os.environ.get("EVOCHORA_RUN_ID", "20260402-11564129-6fe1712c-1779-4256-bbfc-1601ec46b3b8")
DATA_DIR = os.environ.get("EVOCHORA_ANALYSIS_DIR", os.path.join(os.getcwd(), "analysis-data"))
"""Download organism snapshots for a list of tick ranges into DATA_DIR/snap/<tick>.json.

Usage: python3 fetch_snapshots.py START STOP STEP [START STOP STEP ...]
Example (coarse + fine window): python3 fetch_snapshots.py 0 271000001 1000000 150000000 200000001 100000
"""
args=list(map(int,sys.argv[1:]))
ticks=sorted({t for i in range(0,len(args),3) for t in range(args[i],args[i+1],args[i+2])})
out=os.path.join(DATA_DIR,'snap'); os.makedirs(out,exist_ok=True)
def fetch(t):
    f=os.path.join(out,f'{t}.json')
    if os.path.exists(f) and os.path.getsize(f)>0: return
    with urllib.request.urlopen(f'{BASE_URL}/visualizer/api/organisms/{t}?runId={RUN_ID}',timeout=300) as r:
        open(f,'wb').write(r.read())
with ThreadPoolExecutor(4) as ex: list(ex.map(fetch,ticks))
print('fetched',len(ticks),'snapshots into',out)
