import sys
il=sys.argv[1]; out=sys.argv[2]; vocabf=sys.argv[3]
# Pass 1: term -> cf (from "term cf df" header lines; posting lines start with a tab)
term2cf={}
with open(il) as f:
    first=f.readline()  # header "<uniqueTerms> <numDocs>"
    cur=None
    for ln in f:
        if ln[0]=='\t': continue
        p=ln.split()
        if len(p)>=3:
            term2cf[p[0]]=int(p[1])
# assign ids by descending cf (common words -> small ids), tie-break by term
terms_sorted=sorted(term2cf, key=lambda t:(-term2cf[t], t))
term2id={t:i+1 for i,t in enumerate(terms_sorted)}
with open(vocabf,'w') as vf:
    for t in terms_sorted: vf.write(f"{term2id[t]}\t{t}\t{term2cf[t]}\n")
# Pass 2: reconstruct docs. docs[docid] = list of (pos, id)
docs={}
with open(il) as f:
    f.readline()
    curid=None
    for ln in f:
        if ln[0]=='\t':
            p=ln.split()  # docid count pos...
            docid=int(p[0]); tid=curid
            for pos in p[2:]:
                docs.setdefault(docid,[]).append((int(pos),tid))
        else:
            p=ln.split()
            if len(p)>=3: curid=term2id[p[0]]
# emit in docid order
with open(out,'w') as o:
    for docid in sorted(docs):
        seq=[str(tid) for pos,tid in sorted(docs[docid])]
        o.write(f"<DOC>\n<DOCNO>d{docid}</DOCNO>\n<TEXT>{' '.join(seq)}</TEXT>\n</DOC>\n")
print(f"vocab={len(term2id)} docs={len(docs)} tokens={sum(len(v) for v in docs.values())}")
