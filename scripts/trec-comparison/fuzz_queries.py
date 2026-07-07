import random, sys
seed=int(sys.argv[1]); M=int(sys.argv[2]); Vfreq=int(sys.argv[3]) if len(sys.argv)>3 else 800
rng=random.Random(seed)
# integer vocabulary: id 1..80053 exist; bias to frequent (small ids); sprinkle rare + OOV(>80053)
# Dialect-aware term rendering (TASK-0016): Indri terms are bare (`525`), Lucindri terms are quoted
# ("525") — semantically identical, so score comparison is unaffected. The RNG draw is the same either
# way (only the formatting differs), so both dialects still render the same tree via the setstate trick.
DIALECT='indri'
def term():
    r=rng.random()
    if r<0.85: t=str(rng.randint(1,Vfreq))               # frequent
    elif r<0.97: t=str(rng.randint(1,80053))             # any real id
    else: t=str(rng.randint(80054,90000))                # OOV (absent)
    return '"%s"'%t if DIALECT=='lucindri' else t
def weights(n): return [round(rng.uniform(0.1,1.0),2) for _ in range(n)]
# a proximity operand: term / proximity / #syn (never a belief op)
def prox_operand(d):
    if d<=0 or rng.random()<0.6: return term()
    c=rng.choice(['prox','syn'])
    if c=='syn': return "#syn( %s )"%(' '.join(term() for _ in range(rng.randint(2,3))))
    return proximity(d-1)
def proximity(d):
    op=rng.choice(['#1','#od','#uw','#band'])
    k=rng.randint(2,3); ops=' '.join(prox_operand(d-1) for _ in range(k))
    if op=='#band': return "#band( %s )"%ops
    if op=='#uw': return "#uw%d( %s )"%(rng.randint(2,12),ops)
    if op=='#od':
        # Ordered window. #N and Indri's canonical #odN spelling are the same operator in BOTH
        # engines (Indri natively; Lucindri via the TASK-0014 alias), so exercise both spellings —
        # they must produce identical scores. The draw is identical across dialects (same RNG state).
        n=rng.randint(2,6)
        return ("#od%d( %s )" if rng.random()<0.5 else "#%d( %s )")%(n,ops)
    return "#1( %s )"%ops
def belief(d, dialect):
    if d<=0: return term()
    op=rng.choice(['combine','combine','weight','wsum','or','max','syn','prox','filter','term'])
    if op=='term': return term()
    if op=='prox': return proximity(rng.randint(1,2))
    if op=='syn':  return "#syn( %s )"%(' '.join(term() for _ in range(rng.randint(2,3))))
    n=rng.randint(2,4); kids=[belief(d-1,dialect) for _ in range(n)]
    # occasionally add a #not child inside combine
    if op=='combine' and rng.random()<0.2: kids.append("#not( %s )"%belief(d-1,dialect))
    if op=='combine': return "#combine( %s )"%(' '.join(kids))
    if op=='or':      return "#or( %s )"%(' '.join(kids))
    if op=='max':     return "#max( %s )"%(' '.join(kids))
    if op in ('weight','wsum'):
        w=weights(len(kids)); body=' '.join("%s %s"%(wi,k) for wi,k in zip(w,kids))
        return "#%s( %s )"%(op,body)
    if op=='filter':
        cond=proximity(1) if rng.random()<0.4 else term()
        scored=belief(d-1,dialect)
        req=rng.random()<0.5
        if dialect=='indri': return "#%s( %s %s )"%('filreq' if req else 'filrej',cond,scored)
        else:                return "#%s( %s %s )"%('scoreif' if req else 'scoreifnot',cond,scored)
    return term()
out_i=open(sys.argv[4],'w'); out_l=open(sys.argv[5],'w')
for i in range(1,M+1):
    d=rng.randint(1,4)
    # ensure same random draws produce the same tree in both dialects: build once, render twice
    st=rng.getstate(); DIALECT='indri'; qi=belief(d,'indri'); rng.setstate(st); DIALECT='lucindri'; ql=belief(d,'lucindri')
    out_i.write("<query><number>%d</number><text>%s</text></query>\n"%(i,qi))
    out_l.write("<query><number>%d</number><text>%s</text></query>\n"%(i,ql))
print("generated",M,"queries")
