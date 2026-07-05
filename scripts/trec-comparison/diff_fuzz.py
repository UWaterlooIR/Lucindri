import sys, collections
def load(p):
    r=collections.defaultdict(dict)
    for ln in open(p):
        q=ln.split()
        if len(q)>=6: r[q[0]][q[2]]=float(q[4])
    return r
I=load(sys.argv[1]); L=load(sys.argv[2])
qs=sorted(set(I)|set(L), key=int)
maxdeltas=[]; struct=[]
for q in qs:
    di=I.get(q,{}); dl=L.get(q,{})
    shared=set(di)&set(dl)
    if not shared:
        # one side empty / disjoint
        if di or dl: struct.append((q, 'set-disjoint', len(di), len(dl)))
        continue
    md=max(abs(di[d]-dl[d]) for d in shared)
    maxdeltas.append(md)
    # overlap@10
    ti=sorted(di,key=lambda d:-di[d])[:10]; tl=sorted(dl,key=lambda d:-dl[d])[:10]
    ov=len(set(ti)&set(tl))/max(1,min(10,len(ti),len(tl)))
    if md>0.3: struct.append((q, f'score Δ={md:.3f}', ov, ''))
import statistics as st
print(f"queries: {len(qs)}  compared(shared docs): {len(maxdeltas)}")
if maxdeltas:
    print(f"max score-delta per query: median={st.median(maxdeltas):.4f} p90={sorted(maxdeltas)[int(0.9*len(maxdeltas))]:.4f} max={max(maxdeltas):.4f}")
    print(f"  queries with maxΔ <=0.05 (norm noise): {sum(1 for d in maxdeltas if d<=0.05)}")
    print(f"  queries with maxΔ 0.05..0.3: {sum(1 for d in maxdeltas if 0.05<d<=0.3)}")
    print(f"  queries with maxΔ >0.3 (STRUCTURAL): {sum(1 for d in maxdeltas if d>0.3)}")
print(f"\nSTRUCTURAL divergences: {len(struct)}")
for q,sig,a,b in struct[:15]: print(f"  q{q}: {sig}  (I={a} L={b})")
