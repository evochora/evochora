import os
BASE_URL = os.environ.get("EVOCHORA_BASE_URL", "http://localhost:8081")
RUN_ID = os.environ.get("EVOCHORA_RUN_ID", "20260402-11564129-6fe1712c-1779-4256-bbfc-1601ec46b3b8")
DATA_DIR = os.environ.get("EVOCHORA_ANALYSIS_DIR", os.path.join(os.getcwd(), "analysis-data"))
PROTO_DIR = os.environ.get("EVOCHORA_PROTO_DIR", os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "src", "main", "proto"))
os.makedirs(DATA_DIR, exist_ok=True)
import json, glob, os, pickle
from collections import Counter, defaultdict
S=DATA_DIR
chars='0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ'
def short(h):
    n=int(h)
    if n<0: n+=1<<64
    r=''
    for _ in range(6):
        r=chars[n%62]+r; n//=62
    return r
snaps={}
tree={}
for f in glob.glob(os.path.join(DATA_DIR,'snap','*.json')):
    t=int(os.path.basename(f)[:-5])
    try:
        d=json.load(open(f))
    except Exception as e:
        print('bad',f,e); continue
    snaps[t]={'orgs':d['organisms'],'total':d['totalOrganismCount']}
    tree.update(d['genomeLineageTree'])
pickle.dump((snaps,tree),open(os.path.join(DATA_DIR,'snaps.pkl'),'wb'))
print(len(snaps), len(tree))
