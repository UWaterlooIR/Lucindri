// TASK-0013 — Lucindri (Lucene) Krovetz stemmer harness.
// Reads one token per line from stdin, writes "token\tstem" per line to stdout, applying exactly the
// stemmer Lucindri uses in production: Lucene's org.apache.lucene.analysis.en.KStemFilter (the same
// filter EnglishAnalyzerConfigurable wires in when stemmer=KSTEM). We isolate the stemming step with a
// KeywordTokenizer (whole line -> one token) + LowerCaseFilter (KStem expects lowercase) + KStemFilter,
// so tokenization differences are not conflated with stemming differences.
//
// Compile/run against the searcher fat jar (bundles lucene-analysis):
//   javac -cp <fatjar> LucindriKStem.java -d <outdir>
//   java  -cp <fatjar>:<outdir> LucindriKStem < tokens.txt > lucindri_stems.tsv
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.KeywordTokenizer;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.en.KStemFilter;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

public class LucindriKStem {
  public static void main(String[] args) throws Exception {
    BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    PrintStream out = new PrintStream(System.out, true, "UTF-8");
    String token;
    while ((token = in.readLine()) != null) {
      if (token.isEmpty()) {
        continue;
      }
      out.println(token + "\t" + stem(token));
    }
  }

  /** Stem a single token through KeywordTokenizer -> LowerCaseFilter -> KStemFilter. */
  static String stem(String token) throws Exception {
    KeywordTokenizer tok = new KeywordTokenizer();
    tok.setReader(new java.io.StringReader(token));
    TokenStream ts = new KStemFilter(new LowerCaseFilter(tok));
    CharTermAttribute term = ts.addAttribute(CharTermAttribute.class);
    String result = token;
    ts.reset();
    if (ts.incrementToken()) {
      result = term.toString();
    }
    ts.end();
    ts.close();
    return result;
  }
}
