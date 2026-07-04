import sys, collections
def load(path):
    r=collections.defaultdict(list)
    for ln in open(path):
        p=ln.split()
        if len(p)>=6: r[p[0]].append(p[2])   # qid -> ranked docnos
    return r
A=load(sys.argv[1]); B=load(sys.argv[2])
def overlap_at(a,b,k):
    sa=set(a[:k]); sb=set(b[:k]); denom=min(k,len(sa),len(sb))
    return len(sa&sb)/denom if denom else 0.0
def rbo(a,b,p=0.9,k=1000):
    s=0.0; sa=set(); sb=set()
    for d in range(1,k+1):
        if d<=len(a): sa.add(a[d-1])
        if d<=len(b): sb.add(b[d-1])
        s+=(p**(d-1))*(len(sa&sb)/d)
    return (1-p)*s
qids=sorted(set(A)&set(B), key=int)
import statistics as st
cols={'o10':[], 'o100':[], 'o1000':[], 'rbo':[]}
rows=[]
for q in qids:
    o10=overlap_at(A[q],B[q],10); o100=overlap_at(A[q],B[q],100); o1k=overlap_at(A[q],B[q],1000); r=rbo(A[q],B[q])
    cols['o10'].append(o10); cols['o100'].append(o100); cols['o1000'].append(o1k); cols['rbo'].append(r)
    # rank of B's #1 in A, and whether top-1 matches
    top1=1 if A[q][0]==B[q][0] else 0
    rows.append((q,o10,o100,o1k,r,top1))
print(f"queries compared: {len(qids)}")
print(f"{'mean overlap@10':22}= {st.mean(cols['o10']):.3f}")
print(f"{'mean overlap@100':22}= {st.mean(cols['o100']):.3f}")
print(f"{'mean overlap@1000':22}= {st.mean(cols['o1000']):.3f}")
print(f"{'mean RBO(p=0.9)':22}= {st.mean(cols['rbo']):.3f}")
top1matches=sum(r[5] for r in rows)
print(f"{'top-1 doc identical':22}= {top1matches}/{len(qids)} queries")
# worst 6 by overlap@100
rows.sort(key=lambda r:r[2])
print("\nlowest-agreement queries (qid  o@10  o@100  o@1000  RBO  top1match):")
for q,o10,o100,o1k,r,t1 in rows[:6]:
    print(f"  {q}   {o10:.2f}  {o100:.2f}  {o1k:.2f}  {r:.3f}  {t1}")
print("\nhighest-agreement queries:")
for q,o10,o100,o1k,r,t1 in rows[-4:]:
    print(f"  {q}   {o10:.2f}  {o100:.2f}  {o1k:.2f}  {r:.3f}  {t1}")
