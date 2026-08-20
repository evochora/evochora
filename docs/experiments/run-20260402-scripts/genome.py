import os
BASE_URL = os.environ.get("EVOCHORA_BASE_URL", "http://localhost:8081")
RUN_ID = os.environ.get("EVOCHORA_RUN_ID", "20260402-11564129-6fe1712c-1779-4256-bbfc-1601ec46b3b8")
DATA_DIR = os.environ.get("EVOCHORA_ANALYSIS_DIR", os.path.join(os.getcwd(), "analysis-data"))
PROTO_DIR = os.environ.get("EVOCHORA_PROTO_DIR", os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "src", "main", "proto"))
os.makedirs(DATA_DIR, exist_ok=True)
import json, subprocess, sys, os, re
S=DATA_DIR
R=RUN_ID
PROTO=PROTO_DIR
_meta_path=os.path.join(DATA_DIR,'meta.json')
if not os.path.exists(_meta_path):
    subprocess.run(['curl','-s','-m','120',f'{BASE_URL}/visualizer/api/simulation/metadata?runId={RUN_ID}','-o',_meta_path],check=True)
meta=json.load(open(_meta_path))
OPC={int(k):v for k,v in meta['opcodes'].items()}
MT={int(k):v for k,v in meta['moleculeTypes'].items()}
W,H=meta['environment']['shape']
def detail(tick, oid):
    f=f'{S}/det/{tick}_{oid}.json'
    os.makedirs(S+'/det',exist_ok=True)
    if not os.path.exists(f):
        subprocess.run(['curl','-s','-m','120',fBASE_URL+'/visualizer/api/organisms/{tick}/{oid}?runId={R}','-o',f],check=True)
    return json.load(open(f))
def env(tick, x1,x2,y1,y2):
    f=f'{S}/env/{tick}_{x1}_{x2}_{y1}_{y2}.txt'
    os.makedirs(S+'/env',exist_ok=True)
    if not os.path.exists(f):
        pb=subprocess.run(['curl','-s','-m','300',fBASE_URL+'/visualizer/api/environment/{tick}?region={x1},{x2},{y1},{y2}&runId={R}'],capture_output=True,check=True).stdout
        txt=subprocess.run(['protoc','--decode=org.evochora.datapipeline.api.contracts.EnvironmentHttpResponse','-I',PROTO,PROTO+'/org/evochora/datapipeline/api/contracts/http_api_contracts.proto'],input=pb,capture_output=True,check=True).stdout.decode()
        open(f,'w').write(txt)
    txt=open(f).read()
    cells=[]
    for m in re.finditer(r'cells \{\n\s*coordinates: (-?\d+)\n\s*coordinates: (-?\d+)\n(.*?)\n\}', txt, re.S):
        x,y=int(m.group(1)),int(m.group(2))
        d={}
        for line in m.group(3).split('\n'):
            k,v=line.strip().split(': ')
            d[k]=int(v)
        cells.append((x,y,d.get('molecule_type',0),d.get('molecule_value',0),d.get('owner_id',0),d.get('marker',0)))
    return cells
def genome(tick, oid, pad=300):
    d=detail(tick,oid)
    x0,y0=d['staticInfo']['initialPosition']
    x1,x2=max(0,x0-pad),min(W-1,x0+pad); y1,y2=max(0,y0-pad),min(H-1,y0+pad)
    cells=env(tick,x1,x2,y1,y2)
    own=[]
    for x,y,t,v,o,mk in cells:
        if o!=oid: continue
        own.append((x-x0,y-y0,t,v,mk))
    own.sort(key=lambda c:(c[1],c[0]))
    return d, own
def fmt(c):
    dx,dy,t,v,mk=c
    tn=MT.get(t,str(t))
    if tn=='CODE': s=OPC.get(v,f'OP{v}')
    else: s=f'{tn}:{v}'
    return f'({dx:4d},{dy:4d}) {s}'
if __name__=='__main__':
    tick=int(sys.argv[1]); oid=int(sys.argv[2])
    d,own=genome(tick,oid)
    print('org',oid,'born',d['staticInfo']['birthTick'],'pos',d['staticInfo']['initialPosition'],'cells',len(own))
    for c in own: print(fmt(c))
