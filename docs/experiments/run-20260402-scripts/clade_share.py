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
inv={s:h for h in tree for s in [short(h)]}
# also add hashes of living orgs
for t,s in snaps.items():
    for o in s['orgs']: inv.setdefault(short(o['genomeHash']), o['genomeHash'])
A=inv['aKRwb3']
print('aKRwb3 hash', A)
# ancestry chain
chain=[]; g=A
while g is not None:
    chain.append(g); g=tree.get(g)
print('depth', len(chain))
print('ancestors:', ' <- '.join(short(x) for x in chain[:15]))
# first appearance of aKRwb3 organisms (by birthTick)
first=None
for t in sorted(snaps):
    for o in snaps[t]['orgs']:
        if o['genomeHash']==A:
            if first is None or o['birthTick']<first[0]:
                first=(o['birthTick'],o['organismId'],o['parentId'],t)
print('earliest aKRwb3 organism seen:', first)
# clade membership helper
memo={}
def in_clade(g, root):
    key=(g,root)
    if key in memo: return memo[key]
    x=g; path=[]
    r=False
    while x is not None:
        if x==root: r=True; break
        if (x,root) in memo: r=memo[(x,root)]; break
        path.append(x); x=tree.get(x)
    for p in path: memo[(p,root)]=r
    return r
# Show clade share for aKRwb3 and for each ancestor level over time
for t in range(160_000_000, 271_000_001, 5_000_000):
    orgs=[o for o in snaps[t]['orgs'] if not o['isDead']]
    n=len(orgs)
    row=[]
    for anc in [chain[0],chain[1],chain[2],chain[3],chain[5],chain[8]]:
        k=sum(in_clade(o['genomeHash'],anc) for o in orgs)
        row.append(f"{short(anc)}:{k/n*100:5.1f}%")
    print(f"{t/1e6:5.0f}M n={n:4d} "+'  '.join(row))
