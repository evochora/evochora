import os
BASE_URL = os.environ.get("EVOCHORA_BASE_URL", "http://localhost:8081")
RUN_ID = os.environ.get("EVOCHORA_RUN_ID", "20260402-11564129-6fe1712c-1779-4256-bbfc-1601ec46b3b8")
DATA_DIR = os.environ.get("EVOCHORA_ANALYSIS_DIR", os.path.join(os.getcwd(), "analysis-data"))
PROTO_DIR = os.environ.get("EVOCHORA_PROTO_DIR", os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "src", "main", "proto"))
os.makedirs(DATA_DIR, exist_ok=True)
import sys
from genome import genome, fmt, MT, OPC
from collections import Counter
VALUE_MASK=(1<<20)-1
def body(tick,oid, pad_note=True):
    d,own=genome(tick,oid)
    cells=[c for c in own if -1<=c[0]<=110 and -1<=c[1]<=85]
    # anchor label: smallest (x,y) lexicographic among LABELs
    labels=[c for c in cells if MT.get(c[2])=='LABEL']
    anchor=min(labels,key=lambda c:(c[0],c[1]))[3] & VALUE_MASK if labels else 0
    m={}
    for dx,dy,t,v,mk in cells:
        tn=MT.get(t)
        if tn=='DATA': continue
        if tn in ('LABEL','LABELREF'): v=(v & VALUE_MASK) ^ anchor
        m[(dx,dy)]=(tn,v)
    tc=Counter(v[0] for v in m.values())
    print(f"org {oid} born {d['staticInfo']['birthTick']} pos {d['staticInfo']['initialPosition']} bodycells(nonDATA) {len(m)} {dict(tc)} anchor={anchor}")
    return m
def f(c):
    if c is None: return '-'
    tn,v=c
    return OPC.get(v,f'OP{v}') if tn=='CODE' else f'{tn}:{v}'
pairs=[(int(a.split(':')[0]),int(a.split(':')[1])) for a in sys.argv[1:]]
maps=[body(t,o) for t,o in pairs]
for i in range(1,len(maps)):
    a,b=maps[i-1],maps[i]
    print(f"--- diff {pairs[i-1][1]} -> {pairs[i][1]}")
    keys=sorted(set(a)|set(b), key=lambda k:(k[1],k[0]))
    n=0
    for k in keys:
        ca=a.get(k); cb=b.get(k)
        if ca==cb: continue
        n+=1
        if n<=80: print(f'    ({k[0]:4d},{k[1]:3d}) {f(ca):>18s}  =>  {f(cb)}')
    print('   total diffs', n)
