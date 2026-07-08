#!/usr/bin/env python3
"""Run a Lucindri query-parameters file (topics 401-450 etc.) against a RUNNING LucindriServer
(TASK-0019) and write the results in TREC run format:  <topic> Q0 <docno> <rank> <score> <tag>.

  python3 run_server_queries.py --port 8080 --queries queries.lucindri.xml --count 1000 > server.run

Stdlib only. Each <query><number>N</number><text>T</text></query> is POSTed to /search as
{"query": T, "count": count}; results come back already ranked (best first)."""
import argparse, json, sys, urllib.request, urllib.error
import xml.etree.ElementTree as ET


def search(base, query, count):
    body = json.dumps({"query": query, "count": count}).encode()
    req = urllib.request.Request(base + "/search", data=body, method="POST",
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=300) as resp:
        return json.loads(resp.read())["results"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, required=True)
    ap.add_argument("--queries", required=True, help="Lucindri query-parameters XML (401-450)")
    ap.add_argument("--count", type=int, default=1000)
    ap.add_argument("--tag", default="Lucindri")
    a = ap.parse_args()
    base = f"http://{a.host}:{a.port}"

    tree = ET.parse(a.queries)
    root = tree.getroot()
    n_topics = 0
    for q in root.iter("query"):
        number = q.findtext("number").strip()
        text = q.findtext("text").strip()
        try:
            results = search(base, text, a.count)
        except urllib.error.HTTPError as e:
            sys.stderr.write(f"topic {number}: HTTP {e.code} {e.read().decode()}\n")
            continue
        for rank, r in enumerate(results, start=1):
            print(f"{number} Q0 {r['docno']} {rank} {r['score']} {a.tag}")
        n_topics += 1
    sys.stderr.write(f"ran {n_topics} topics\n")


if __name__ == "__main__":
    main()
