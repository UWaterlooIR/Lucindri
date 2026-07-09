Lucindri
========

Lucindri is an open-source implementation of Indri search logic and structured query language using the Lucene Search Engine.  Lucindri consists of two components: the indexer and the searcher.

## Relationship to C++ Indri

Up to the git tag **`indri-parity`** (commit `91d2451`, 2026-07-06), the Lucindri query language is a
reimplementation of the [C++ Indri 5.21](https://www.lemurproject.org/indri.php) query language,
matching Indri's semantics and scores to the extent documented in
[`docs/indri-query-language.md`](docs/indri-query-language.md) (§6 lists the small, deliberate
divergences) and [`docs/lucindri-vs-indri-scores.md`](docs/lucindri-vs-indri-scores.md).

**From that point on (TASK-0016 onward), Lucindri deliberately extends beyond the Indri query
language**: string-literal text splices (`"..."`), the `#token(...)` verbatim vocabulary lookup, and
the removal of field-restriction syntax (`term.field` / `:`) are Lucindri language design, not Indri
reimplementation. Each extension or divergence is cataloged in the query-language guide's
compatibility ledger (see `tasks/TASK-0016.md` and `docs/indri-query-language.md`). To see everything
added beyond the Indri-parity point: `git diff indri-parity..master`.

## Getting Started
Lucindri requires the 64-bit version of Java 11 and Apache [Maven](https://maven.apache.org/download.cgi).

Development and testing (including the post-1.5 updates) were done on **64-bit OpenJDK 11** — specifically OpenJDK 11.0.31 on Ubuntu under WSL2 — with **Maven 3.6.3**. Any 64-bit Java 11 JDK should work.

Clone this repository and, from the repo root, run:

```
mvn clean install
```

The root `pom.xml` is a Maven **reactor**: it builds every module (`LucindriAnalyzer`, `LucindriSearcher`,
`LucindriIndexer`) in the correct dependency order automatically — no need to build them one at a time. To
build a single module and just the modules it depends on, use e.g. `mvn -pl LucindriSearcher -am install`.
(All modules share the version **2.0**.)

> **Note on trec-car-tools.** The *only* thing that depends on [trec-car-tools](https://github.com/TREMA-UNH/trec-car-tools-java) (from the Trema Lab at UNH) is the `car` document-format parser used at index time. That dependency is declared in `LucindriIndexer/pom.xml` and now resolves automatically from [jitpack.io](https://jitpack.io) during the build — you no longer need to clone or `mvn install` it by hand. Nothing else in the engine — the searcher, the analyzer, or any other document format — uses it, so if you never index the `car` format you never touch it.


## Lucindri Indexer
The main class in indexer is: org.lemurproject.lucindri.indexer.BuildIndex.  This program takes a single properties file as an argument.  See index.properties in the indexer directory as an example.

As of release 1.1, Lucindri supports indexing in Solr. **Note:** the Solr indexing path was *not* exercised or tested during the post-1.5 updates — the Lucene indexing path (`indexingPlatform=lucene`) is the one that is actively tested and supported here.

Description of indexing properties:
```
#implementation options
# documentFormat options = text, wsj, gov2, indrigov2, json, wapo, warc, trectext, cw09, cw12, cw22, car, marco, marcofull, climbmix
documentFormat=[text | wsj | gov2 | indrigov2 | json | wapo | warc | trectext | cw09 | cw12 | cw22 | car | marco | marcofull | climbmix]
# indexing platform options = lucene, solr
indexingPlatform=[lucene|solr]

#data options
dataDirectory=[Directory or file where data is] 
indexDirectory=[Directory where index will be written]
indexName=[Name of the index]

#field options
#If index.fulltext is set to true, a field with all document text is created.  This is recommended.
#fulltext is the default field for queries if it is indexed
indexFullText=[true (recommended) | false]
fieldNames=[Comma separated list of field names to be stored (e.g. title, url, body)]

#analyzer options
stemmer=[kstem | porter | none]
removeStopwords=[true | false]
ignoreCase=[true | false]

#scoring options
# When true, also store each indexed text field's exact token count so the searcher scores with the
# exact document length |d| (matching C++ Indri) instead of Lucene's lossy 1-byte norm. Costs ~2
# bytes/doc. Default false (norm; unchanged). Auto-detected at query time — no searcher setting needed.
exactDocumentLength=[true | false (default)]

#solr options
host=[host name or IP]
port=[port number]
```

Example index.properties:
```
#implementation options
# documentFormat options = text, wsj, gov2, indrigov2, json, wapo, warc, trectext, cw09, cw12, cw22, car, marco, marcofull, climbmix
documentFormat=cw09

#data options
dataDirectory=/usr/home/data/cw09data
indexDirectory=/usr/home/
indexName=CW09_lucindri_index

#field options
#If index.fulltext is set to true, a field with all document text is created.  This is recommended.
#fulltext is the default field for queries if it is indexed
indexFullText=true
fieldNames=title,url

#analyzer options
stemmer=kstem
removeStopwords=true
ignoreCase=true
```

Running the LucindriIndexer can be done from inside an IDE, invoking the main class (org.lemurproject.lucindri.indexer.BuildIndex), or using the jar file in the *target* directory.  Use at least 2G of heap space (preferably 4G - 8G).
```
java -jar -Xmx4G LucindriIndexer-2.0-jar-with-dependencies.jar index.properties
```

## Lucindri Searcher
The Lucindri Searcher has Indri Dirichlet and Jelinek-Mercer smoothing rules (a.k.a. Similarity in Lucene) implemented.  The results are printed in TREC format.

The main class in searcher is: org.lemurproject.lucindri.searcher.IndriSearch.  It takes an xml parameter file, which contains queries, as an argument.  The query parameters follow the same format as Indri.  

### Retrieval Parameters
+ **index:** path to an Indri Repository. Specified as <index>/path/to/repository</index> in the parameter file and as -index=/path/to/repository on the command line. This element can be specified multiple times to combine Repositories.
+ **count:** an integer value specifying the maximum number of results to return for a given query. Specified as <count>number</count> in the parameter file and as -count=number on the command line.
+ **query:** An indri query language query to run. This element can be specified multiple times.
+ **rule:** specifies the smoothing rule (TermScoreFunction) to apply.
  + Format of the rule is: ( key ":" value ) [ "," key ":" value ]*

**Valid methods:**
+ dirichlet
(also 'd', 'dir') (default mu=2000)
+ jelinek-mercer
(also 'jm', 'linear') (default collectionLambda=0.4), collectionLambda is also known as just "lambda"

**Query-time analysis parameters.** These control how each query's text is tokenized before matching, and are specified once per parameter file:
+ **stemmer:** `<stemmer>kstem</stemmer>` — one of `kstem` (a.k.a. `krovetz`), `porter`, or `none`. Default `kstem`.
+ **removeStopwords:** `<removeStopwords>true</removeStopwords>` — drop English stopwords from queries. Default `true`.
+ **ignoreCase:** `<ignoreCase>true</ignoreCase>` — lowercase query terms. Default `true`.

> **Important:** query-time analysis must match the analysis used to build the index (the same `stemmer` / `removeStopwords` / `ignoreCase`). If they differ, query terms will not match the indexed terms and you will get few or no results.

Here is an example rule in parameter file format:
```
<rule>dirichlet:2000</rule>
```
This corresponds to Dirichlet smoothing with mu equal to 2000. Jelinek-Mercer smoothing is specified the same way, e.g.:
```
<rule>jm:.3</rule>
```

Here is an example query file:
```
<parameters>
        <index>PATH_TO_INDEX</index>
        <trecFormat>true</trecFormat>
        <rule>dirichlet:2000</rule>
        <count>100</count>
  <query>
    <number> 51 </number>
    <text>#5("president clinton")</text>
  </query>
  <query>
     <number> 52 </number>
     <text> #combine( "avp" ) </text>
   </query>
</parameters>
```

Running the LucindriSearcher can be done from inside an IDE, invoking the main class (org.lemurproject.lucindri.searcher.IndriSearch), or using the jar file in the *target* directory.  Use at least 2G of heap space (preferably 4G - 8G).
```
java -jar -Xmx4G LucindriSearcher-2.0-jar-with-dependencies.jar queries.xml
```

### Fetching a document by docno (`getdoc`)

The searcher jar also exposes a `getdoc` subcommand that prints a single document's stored `fulltext` to
stdout, given the index and the document's external id (docno). It is an exact keyword lookup — no query
analysis — and is handy for inspecting a result or serving full documents:
```
java -jar LucindriSearcher-2.0-jar-with-dependencies.jar getdoc /path/to/index shard_00000_0
```
The index may be a comma-separated list (searched as one, like the `<index>` parameter). Exit status is
`0` when the docno is found, `1` when it is not. Running the jar with a queries file as the first argument
(as above) is unchanged — the `getdoc` subcommand is purely additive.

## Lucindri Server (HTTP/JSON)

For interactive use (e.g. an agent issuing one query at a time), the **`LucindriServer`** module runs a
long-lived HTTP/JSON service that opens the index once and stays warm, so each query is answered at
sub-second latency instead of paying JVM startup + index-open per query. It uses the JDK's built-in HTTP
server (no web framework) and shares the exact same retrieval path as the batch searcher. Main class:
`org.lemurproject.lucindri.server.LucindriServer`.

```
java -jar LucindriServer-2.0-jar-with-dependencies.jar --index /path/to/index --port 8080
     [--host 127.0.0.1] [--rule dirichlet:2000] [--stemmer kstem]
     [--removeStopwords true] [--ignoreCase true]
     [--maxPassages 4] [--maxSummaryWords 75] [--threads N]
```

`--index` and `--port` are required; the rest default to the batch searcher's defaults. The analysis
options (`stemmer`/`removeStopwords`/`ignoreCase`) must match how the index was built. Binds loopback by
default (dev target; no auth). Requests and responses are logged to stdout.

Summaries (`summaries=true`) are query-biased extractive snippets: up to `--maxPassages` sentences chosen
by query relevance, **joined by a single space**, and hard-capped at `--maxSummaryWords` whitespace-separated
words — a longer summary is truncated at a word boundary and marked with a trailing `" ..."`. On messy
docs with no sentence breaks (SEO/legal run-ons) the cap keeps the document's first `--maxSummaryWords`
words.

Endpoints (all bodies are JSON):
- **`POST /search`** — `{ "query": string, "count": int, "summaries": bool? }` →
  `{ "results": [ { "docno": string, "score": number, "summary": string? } ] }`. `summary` is present only
  when `summaries=true` (a query-biased extractive snippet). A malformed query → **400** `{ "error": ... }`.
- **`POST /document`** — `{ "docno": string }` → `{ "docno": string, "fulltext": string }`, or **404** if
  the docno is unknown.
- **`GET /healthz`** → `{ "ok": true }` once the index is open.

A stdlib black-box acceptance harness is provided: `python3 scripts/conformance.py --port <p> --query
'#combine("<a matching term>")'` (see `scripts/server-smoke.sh` for an end-to-end build-index-and-check run).

## Lucindri Query Language

### Query text: all text is quoted (TASK-0016)

**Every piece of query text is written inside a `"..."` string literal.** A literal is an *analyzed-text
splice*: Lucindri runs the index-time analyzer over its contents and splices the resulting tokens into the
enclosing operator, so query tokens equal index tokens by construction — you can type `"U.S.A."`,
`"google.com"`, `"section 3.14"`, or a whole phrase and it tokenizes exactly as the document did. The only
escapes inside a literal are `\"` and `\\`.

- **A literal is a bag of tokens, not a phrase.** `#combine("dog cat")` ≡ `#combine("dog" "cat")`. For a
  **phrase**, use a proximity operator: `#1("dog cat")`.
- **`#token("..." "..." …)`** is the *verbatim* escape hatch — each quoted token is looked up as-is, with
  **no** tokenization/stemming/stopping/lowercasing. Use it for an already-tokenized vocabulary entry whose
  surface form can't be re-typed, e.g. `#token("u.s.a")`.
- **Bare unquoted terms are a syntax error** (`#combine(dog cat)` is rejected — write `#combine("dog cat")`).
  This closes the old "typo silently becomes a query" trap. **Field restriction (`term.field` / `:`) and the
  `~` operator prefix are removed.** The default field is `fulltext`.
- A query with no leading operator (`"dog training"`) is wrapped in an implicit `#combine`.

**Weights copy per token.** In a weighted operator, a multi-token literal gives *each* token the operand's
weight, and since `#weight` normalizes by the sum of child weights this shifts the mass split:
`#weight( 2.0 "hi-tech lamp" 1.0 "cat" )` builds `(2.0 hi)(2.0 tech)(2.0 lamp)(1.0 cat)` (the literal's
tokens jointly get 6/7 of the mass). To weight a *concept* once, wrap it:
`#weight( 2.0 #combine("this is my first concept") 1.0 "cat" )`.

### Lucindri implements these Indri belief operators:

Every query node produces a **belief** — a smoothed probability that the term/concept is present in the document (for a term under Dirichlet smoothing, `(tf + μ·P(w|C)) / (|d| + μ)`, carried as a log-probability). Belief operators combine their children's beliefs into the document score. (See `docs/indri-query-language.md` for the scoring details.)

+ #combine — the belief-AND (Indri's canonical operator).
  + Example: #combine("dog training")
  + Note: `#and` is a **Lucindri-only alias** for `#combine`. Indri itself has no `#and` operator, so prefer `#combine` for portability.
+ #weight (weighted combine) and #wand (weighted and) — both apply per-operand weights
  + `#weight` and `#wand` are the **same** operator in Indri (both a weighted `#combine`); use either.
  + Example: #weight(0.2 "president" 0.8 "obama")
  + Example: #wand(0.2 "president" 0.8 "obama")
+ #or
  + Example: #or("dog cat")
+ #not
  + Example: #combine("president" #not("obama"))
+ #wsum (weighted sum)
  + Example: #wsum(0.2 "president" 0.8 "obama")
+ #max
  + Example: #max("dog train") - scores each document by the larger of the two terms' beliefs (not their combination)
+ #scoreif (filter require)
  + Example: #scoreif( "sheep" #combine("dolly cloning") ) - only consider documents matching "sheep" and rank them by #combine("dolly cloning")
+ #scoreifnot (filter reject)
  + Example: #scoreifnot( "parton" #combine("dolly cloning") ) - only consider documents NOT matching "parton" and rank them by #combine("dolly cloning")

And these term (proximity) operators:
+ #band (boolean and)
  + #band("Q") is scored as #uw("Q") - an unordered window of the length of the document
+ #N (ordered window)
  + ordered window - terms must appear ordered, with at most N-1 terms between each
  + Example: #2("white house") - matches "white * house" (where * is any word or null)
  + `#N` and Indri's canonical `#odN` spelling are the **same** ordered window (`#od5` ≡ `#5`). The `#nearN` and `#windowN` spellings are not supported and error.
+ #uwN (unordered window)
  + unordered window - all terms must appear within window of length N in any order
  + Example: #uw2("white house") - matches "white house" and "house white"
+ #syn (synonym)
  + Example: #syn( #1("united states") #1("united states of america") )

And the text splices:
+ #token (verbatim vocabulary lookup) — quoted tokens used as-is, no analysis
  + Example: #combine( #token("u.s.a") "great state" )

**Nesting rule.** The operands of a proximity operator (`#N`, `#uwN`, `#band`, `#syn`) must themselves produce positions — a `"..."` literal, a `#token(...)`, or another proximity operator. A belief operator (`#combine`, `#or`, ...) is not a valid operand of a proximity operator; to express a disjunctive facet inside a proximity operator use `#syn` (not `#or`).

### Not (yet) implemented
These Indri operators/features are not implemented in Lucindri and are **rejected with a clear error** — the parser never silently degrades an unrecognized `#operator` to `#and`: `#wsyn` (weighted synonym), `#prior` (document priors), passage/field/extent retrieval (`#combine[field]`, `#combine[passageN:M]`, subquery field restriction), numeric and date field operators (`#less`, `#greater`, `#between`, `#equals`, `#date:*`), and wildcards (`dog*`). The `#filreq` / `#filrej` filter names are not recognized either — use `#scoreif` / `#scoreifnot` instead.
