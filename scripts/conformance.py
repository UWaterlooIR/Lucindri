#!/usr/bin/env python3
"""Lucindri server conformance harness (TASK-0019): black-box checks that a RUNNING
server satisfies the ISJ/Cottontail wire contract. Stdlib only.

  python3 conformance.py --port 8080 --query '#combine("mountain")'

--query must be a VALID Indri query that matches >=1 document in the served index (the
implementer picks one for their test index). Use an explicit operator with quoted text,
e.g. #combine("mountain"), and shell-quote the whole value as shown. Exits non-zero on
any failure.
"""
import argparse, json, sys, urllib.request, urllib.error

def req(base, path, body=None, method="GET"):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(base + path, data=data, method=method,
                               headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(r, timeout=120) as resp:
            return resp.status, json.loads(resp.read() or b"null")
    except urllib.error.HTTPError as e:
        try: return e.code, json.loads(e.read() or b"null")
        except Exception: return e.code, None

RESULTS = []
def check(name, ok, detail=""):
    RESULTS.append(ok)
    print(f"[{'PASS' if ok else 'FAIL'}] {name}" + (f"  -- {detail}" if detail and not ok else ""))

def finish():
    n, p = len(RESULTS), sum(RESULTS)
    print(f"\n{p}/{n} checks passed")
    sys.exit(0 if p == n else 1)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, required=True)
    ap.add_argument("--query", required=True,
                    help="a valid Indri query (text quoted) matching >=1 doc in the served index")
    a = ap.parse_args()
    base = f"http://{a.host}:{a.port}"

    st, b = req(base, "/healthz")
    check("GET /healthz -> 200 {ok:true}", st == 200 and isinstance(b, dict) and b.get("ok") is True, f"{st} {b}")

    st, b = req(base, "/search", {"query": a.query, "count": 5}, "POST")
    res = b.get("results") if isinstance(b, dict) else None
    ok = st == 200 and isinstance(res, list) and len(res) >= 1
    check("POST /search -> 200 with >=1 result", ok, f"{st} {b}")
    if not ok:
        print("cannot continue without a search result; pick a --query that matches your index")
        finish()
    first = res[0]
    check("result.docno is a non-empty string", isinstance(first.get("docno"), str) and first["docno"] != "", f"{first}")
    check("result.score is numeric", isinstance(first.get("score"), (int, float)) and not isinstance(first.get("score"), bool), f"{first}")
    check("no summary when not requested", first.get("summary") in (None,) or "summary" not in first, f"{first}")
    check("count respected (<=5)", len(res) <= 5, f"got {len(res)}")
    scores = [r.get("score") for r in res]
    check("results non-increasing by score", all(scores[i] >= scores[i+1] for i in range(len(scores)-1)), f"{scores}")

    st2, b2 = req(base, "/search", {"query": a.query, "count": 5}, "POST")
    d1 = [(r["docno"], r["score"]) for r in res]
    d2 = [(r["docno"], r["score"]) for r in (b2.get("results") if isinstance(b2, dict) else [])]
    check("same query -> identical results (determinism)", st2 == 200 and d1 == d2, "results differ")

    st, b = req(base, "/search", {"query": a.query, "count": 3, "summaries": True}, "POST")
    sres = b.get("results") if isinstance(b, dict) else None
    # Every result must carry a non-empty summary (the leading-sentence fallback guarantees this even for
    # hits that match no query term), not just the top one.
    check("summaries=true -> every result has a non-empty summary",
          isinstance(sres, list) and len(sres) >= 1
          and all(isinstance(r.get("summary"), str) and r.get("summary") != "" for r in sres), f"{st} {b}")

    st, b = req(base, "/search", {"query": "#combine(", "count": 5}, "POST")
    check("malformed query -> 400 + error message", st == 400 and isinstance(b, dict) and bool(b.get("error")), f"{st} {b}")

    st, b = req(base, "/search", {"count": 5}, "POST")
    check("missing query -> 400", st == 400, f"{st} {b}")

    st, b = req(base, "/document", {"docno": first["docno"]}, "POST")
    ok = (st == 200 and isinstance(b, dict) and b.get("docno") == first["docno"]
          and isinstance(b.get("fulltext"), str) and b["fulltext"] != "")
    check("POST /document(known docno) -> 200, docno echoed, non-empty fulltext", ok, f"{st} {b}")

    st, b = req(base, "/document", {"docno": "__definitely_not_a_docno__"}, "POST")
    check("POST /document(unknown) -> 404", st == 404, f"{st} {b}")

    finish()

if __name__ == "__main__":
    main()
