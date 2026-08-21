import os
BASE_URL = os.environ.get("EVOCHORA_BASE_URL", "http://localhost:8081")
RUN_ID = os.environ.get("EVOCHORA_RUN_ID", "20260402-11564129-6fe1712c-1779-4256-bbfc-1601ec46b3b8")
DATA_DIR = os.environ.get("EVOCHORA_ANALYSIS_DIR", os.path.join(os.getcwd(), "analysis-data"))
PROTO_DIR = os.environ.get("EVOCHORA_PROTO_DIR", os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "src", "main", "proto"))
os.makedirs(DATA_DIR, exist_ok=True)
import pickle
from collections import Counter
from snapshots_load import short
snaps,tree=pickle.load(open(os.path.join(DATA_DIR,'snaps.pkl'),'rb'))
ticks=sorted(snaps)
for t in range(150_000_000, 200_000_001, 2_000_000):
    orgs=[o for o in snaps[t]['orgs'] if not o['isDead']]
    c=Counter(o['genomeHash'] for o in orgs)
    top=c.most_common(5)
    print(f"{t/1e6:6.0f}M n={len(orgs):4d} ", ' '.join(f"{short(g)}:{n}" for g,n in top))
