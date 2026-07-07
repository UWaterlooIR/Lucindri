package org.lemurproject.lucindri.indexer.documentparser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

import org.lemurproject.lucindri.indexer.domain.IndexingConfiguration;
import org.lemurproject.lucindri.indexer.domain.ParsedDocument;
import org.lemurproject.lucindri.indexer.domain.ParsedDocumentField;

/**
 * Document parser for the climbmix corpus: gzipped JSON-Lines shards, one JSON
 * object per line. Selected by {@code documentFormat=climbmix}.
 *
 * Each record maps as:
 * <ul>
 * <li>{@code docid} -&gt; stored field {@code externalId} (the TREC DOCNO the searcher prints)</li>
 * <li>{@code contents} -&gt; stored field {@code fulltext} (the default query field)</li>
 * </ul>
 * All other keys (e.g. {@code id}, {@code source_file}, {@code row_number},
 * {@code mode}) are ignored.
 *
 * The parser holds a single {@link BufferedReader} open at a time and advances
 * to the next file when the current one is exhausted, so memory stays constant
 * regardless of corpus size. Files ending in {@code .gz} are decompressed on the
 * fly via {@link GZIPInputStream}; no decompressed copy is written to disk.
 */
public class ClimbmixJsonlDocumentParser extends DocumentParser {

	private static final Logger logger = Logger.getLogger(ClimbmixJsonlDocumentParser.class.getName());

	private static final String DOCID_KEY = "docid";
	private static final String CONTENTS_KEY = "contents";

	private final Iterator<File> fileIterator;
	private final Gson gson = new Gson();

	private BufferedReader br;
	private int docNum = 0;
	private long skippedMissingContents = 0;
	private long skippedMalformed = 0;
	private boolean summaryPrinted = false;

	public ClimbmixJsonlDocumentParser(IndexingConfiguration options) throws IOException {
		List<File> files = new ArrayList<>();
		File root = new File(options.getDataDirectory());
		if (root.isFile()) {
			files.add(root);
		} else {
			listFiles(root, files);
		}
		Collections.sort(files); // deterministic shard order
		fileIterator = files.iterator();
		openNext();
	}

	private static void listFiles(File dir, List<File> out) {
		File[] fs = dir.listFiles();
		if (fs == null) {
			return;
		}
		for (File f : fs) {
			if (f.isFile()) {
				out.add(f);
			} else if (f.isDirectory()) {
				listFiles(f, out);
			}
		}
	}

	private void openNext() throws IOException {
		if (fileIterator.hasNext()) {
			File f = fileIterator.next();
			InputStream in = new FileInputStream(f);
			if (f.getName().endsWith(".gz")) {
				in = new GZIPInputStream(in);
			}
			br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
		} else {
			br = null;
		}
	}

	/**
	 * Returns null if the key is absent or not a JSON string/primitive, so extra
	 * or oddly-typed keys never crash the run.
	 */
	private static String optString(JsonObject obj, String key) {
		if (obj != null && obj.has(key)) {
			JsonElement e = obj.get(key);
			if (e != null && e.isJsonPrimitive()) {
				return e.getAsString();
			}
		}
		return null;
	}

	@Override
	public boolean hasNextDocument() {
		return br != null;
	}

	@Override
	public ParsedDocument getNextDocument() throws IOException {
		String line;
		while (br != null) {
			if ((line = br.readLine()) == null) {
				br.close();
				openNext();
				continue;
			}
			if (line.isEmpty()) {
				continue;
			}

			JsonObject obj;
			try {
				obj = gson.fromJson(line, JsonObject.class);
			} catch (JsonSyntaxException e) {
				skippedMalformed++;
				continue;
			}

			String contents = optString(obj, CONTENTS_KEY);
			if (contents == null) {
				// Nothing to index; the writer would skip a null field anyway.
				skippedMissingContents++;
				continue;
			}
			String docid = optString(obj, DOCID_KEY);

			docNum++;
			ParsedDocument doc = new ParsedDocument();
			doc.setDocumentFields(new ArrayList<ParsedDocumentField>());
			doc.addDocumentField(new ParsedDocumentField(EXTERNALID_FIELD,
					docid != null ? docid : String.valueOf(docNum), false));
			doc.addDocumentField(new ParsedDocumentField(INTERNALID_FIELD, String.valueOf(docNum), false));
			doc.addDocumentField(new ParsedDocumentField(FULLTEXT_FIELD, contents, false));
			return doc;
		}

		printSummaryOnce();
		return null;
	}

	private void printSummaryOnce() {
		if (!summaryPrinted) {
			summaryPrinted = true;
			logger.log(Level.INFO, "climbmix parser: emitted {0}, skipped {1} (missing contents), {2} (malformed)",
					new Object[] { docNum, skippedMissingContents, skippedMalformed });
			if (skippedMissingContents > 0 || skippedMalformed > 0) {
				System.out.println("climbmix parser: skipped " + skippedMissingContents
						+ " record(s) with missing contents, " + skippedMalformed + " malformed line(s).");
			}
		}
	}

}
