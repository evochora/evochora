import os
BASE_URL = os.environ.get("EVOCHORA_BASE_URL", "http://localhost:8081")
RUN_ID = os.environ.get("EVOCHORA_RUN_ID", "20260402-11564129-6fe1712c-1779-4256-bbfc-1601ec46b3b8")
DATA_DIR = os.environ.get("EVOCHORA_ANALYSIS_DIR", os.path.join(os.getcwd(), "analysis-data"))
PROTO_DIR = os.environ.get("EVOCHORA_PROTO_DIR", os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "src", "main", "proto"))
os.makedirs(DATA_DIR, exist_ok=True)
import sys
from genome import genome, MT, OPC
VALUE_MASK=(1<<20)-1
def row(tick,oid,y):
    d,own=genome(tick,oid)
    cells=[c for c in own if -1<=c[0]<=110 and -1<=c[1]<=85]
    labels=[c for c in cells if MT.get(c[2])=='LABEL']
    anchor=min(labels,key=lambda c:(c[0],c[1]))[3] & VALUE_MASK
    out=[]
    for dx,dy,t,v,mk in sorted(cells,key=lambda c:(c[1],c[0])):
        if dy!=y: continue
        tn=MT.get(t)
        if tn=='CODE': s=OPC.get(v,f'OP{v}')
        elif tn in('LABEL','LABELREF'): s=f'{tn[:3]}:{(v&VALUE_MASK)^anchor}'
        elif tn=='REGISTER': s=f'R{v}'
        elif tn=='DATA': s=f'D{v}'
        else: s=f'{tn}:{v}'
        out.append(f'{dx}:{s}')
    print(f'org {oid} row {y}:', ' '.join(out))
tick=int(sys.argv[1]); y=int(sys.argv[2])
for oid in sys.argv[3:]: row(tick,int(oid),y)
