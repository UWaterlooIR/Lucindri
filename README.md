Lucindri
========

Lucindri is an open-source implementation of Indri search logic and structured query language using the Lucene Search Engine.  Lucindri consists of two components: the indexer and the searcher.

## Getting Started
Lucindri requires the 64-bit version of Java 11 and Apache [Maven](https://maven.apache.org/download.cgi).

Development and testing (including the post-1.5 updates) were done on **64-bit OpenJDK 11** — specifically OpenJDK 11.0.31 on Ubuntu under WSL2 — with **Maven 3.6.3**. Any 64-bit Java 11 JDK should work.

Clone this repository and build the three modules with *mvn clean install* in this order:
+ LucindriAnalyzer
+ LucindriSearcher
+ LucindriIndexer

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
java -jar -Xmx4G LucindriIndexer-1.45-jar-with-dependencies.jar index.properties
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
    <text>#5(president clinton)</text>
  </query>
  <query>
     <number> 52 </number>
     <text> #combine( avp ) </text>
   </query>
</parameters>
```

Running the LucindriSearcher can be done from inside an IDE, invoking the main class (org.lemurproject.lucindri.searcher.IndriSearch), or using the jar file in the *target* directory.  Use at least 2G of heap space (preferably 4G - 8G).
```
java -jar -Xmx4G LucindriSearcher-1.5-jar-with-dependencies.jar queries.xml
```

## Lucindri Query Language

### Lucindri Fields
Lucindri documents are stored in fields, which are specified at index time.  If indexFullText is set to true during indexing, a *fulltext* field is created and is used as the default query field if no field is specified.

You can search any field by typing the term you are looking for followed by a period "." and then the field name.

For example:
```
President.fulltext Obama.title
```

> **Caveat — field behavior is UNTESTED against Indri.** All of Lucindri's conformance work (score/rank comparisons vs C++ Indri) used only the default `fulltext` field. Field-restricted scoring (`term.field`), documents with multiple named fields, and repeated/duplicate fields of the same name have **not** been differentially tested against Indri, so their equivalence to Indri's behavior is **unknown**. Known concerns include how the document length (`|d|`) and collection statistics are scoped for a field-restricted term, and how proximity behaves across repeated field values (Lucindri concatenates same-name fields with no position gap). Treat field queries as **not yet verified for Indri parity**. (Differential testing is deferred — see `tasks/TASK-0017.md`.)

If a query has no leading operator (e.g. `dog training`), Lucindri wraps the terms in `#combine`.

### Lucindri implements these Indri belief operators:

Every query node produces a **belief** — a smoothed probability that the term/concept is present in the document (for a term under Dirichlet smoothing, `(tf + μ·P(w|C)) / (|d| + μ)`, carried as a log-probability). Belief operators combine their children's beliefs into the document score. (See `docs/indri-query-language.md` for the scoring details.)

+ #combine (equivalent to #and)
  + Example: #combine(dog training)
+ #weight (weighted combine) and #wand (weighted and) — both apply per-operand weights
  + Example: #weight(0.2 president 0.8 obama)
  + Example: #wand(0.2 president 0.8 obama)
+ #or
  + Example: #or(dog cat)
+ #not
  + Example: #and(president #not(obama))
+ #wsum (weighted sum)
  + Example: #wsum(0.2 president 0.8 obama)
+ #max
  + Example: #max(dog train) - scores each document by the larger of the two terms' beliefs (not their combination)
+ #scoreif (filter require)
  + Example: #scoreif( sheep #combine(dolly cloning) ) - only consider those documents matching the query "sheep" and rank them according to the query #combine(dolly cloning)
+ #scoreifnot (filter reject)
  + Example: #scoreifnot( parton #combine(dolly cloning) ) - only consider those documents NOT matching the query "parton" and rank them according to the query #combine(dolly cloning)

And these term (proximity) operators:
+ #band (boolean and)
  + #band(Q) is scored as #uw(Q) - an unordered window of the length of the document
+ #N (ordered window)
  + ordered window - terms must appear ordered, with at most N-1 terms between each
  + Example: #2(white house) - matches "white * house" (where * is any word or null)
  + `#N` and Indri's canonical `#odN` spelling are the **same** ordered window (`#od5` ≡ `#5`). The `#nearN` and `#windowN` spellings are not supported and error.
+ #uwN (unordered window)
  + unordered window - all terms must appear within window of length N in any order
  + Example: #uw2(white house) - matches "white house" and "house white"
+ #syn (synonym)
  + Example: #syn( #1(united states) #1(united states of america) )

**Nesting rule.** The operands of a proximity operator (`#N`, `#uwN`, `#band`, `#syn`) must themselves produce positions — a plain term or another proximity operator. A belief operator (`#combine`, `#or`, ...) is not a valid operand of a proximity operator; to express a disjunctive facet inside a proximity operator use `#syn` (not `#or`).

### Not (yet) implemented
These Indri operators/features are not implemented in Lucindri and are **rejected with a clear error** — the parser never silently degrades an unrecognized `#operator` to `#and`: `#wsyn` (weighted synonym), `#prior` (document priors), passage/field/extent retrieval (`#combine[field]`, `#combine[passageN:M]`, subquery field restriction), numeric and date field operators (`#less`, `#greater`, `#between`, `#equals`, `#date:*`), and wildcards (`dog*`). The `#filreq` / `#filrej` filter names are not recognized either — use `#scoreif` / `#scoreifnot` instead.
