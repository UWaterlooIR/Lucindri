package org.lemurproject.lucindri.indexer.documentparser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.StringJoiner;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;
import org.lemurproject.lucindri.indexer.domain.IndexingConfiguration;
import org.lemurproject.lucindri.indexer.domain.ParsedDocument;
import org.lemurproject.lucindri.indexer.domain.ParsedDocumentField;
import org.xml.sax.SAXException;

/**
 * Parser for standard TREC-text: documents delimited by {@code <DOC>}…{@code </DOC>}, the document
 * id in {@code <DOCNO>…</DOCNO>} (anywhere inside the document), and text content in include tags —
 * by default {@code <TEXT>}, configurable via the {@code contentTags} property. The document id is
 * stored under {@code externalId} and the markup-free content under {@code fulltext}. Mirrors
 * Indri's trectext semantics (see C++ Indri {@code FileClassEnvironmentFactory.cpp}).
 */
public class TrecTextDocumentParser extends DocumentParser {

	private final static String EXTERNALID_FIELD = "externalId";
	private final static String ID_FIELD = "internalId";
	private final static String BODY_FIELD = "body";
	private final static String TEXT_FIELD = "text";
	private final static String TITLE_FIELD = "title";
	private final static String HEADING_FIELD = "heading";
	private final static String URL_FIELD = "url";

	private int docNum;
	private Iterator<File> fileIterator;
	private BufferedReader br;
	private final List<String> fieldsToIndex;
	private final boolean indexFullText;
	private final List<String> contentTags;

	public TrecTextDocumentParser(IndexingConfiguration options) throws IOException {
		List<File> files = new ArrayList<>();
		listFiles(options.getDataDirectory(), files);
		fileIterator = files.iterator();
		getNextScanner();
		docNum = 0;

		// Null-safe: empty fieldNames must not NPE.
		List<String> fields = options.getIndexFields();
		fieldsToIndex = (fields != null) ? fields : Collections.emptyList();
		indexFullText = options.isIndexFullText();

		// Content tags contributing to fulltext; default is TEXT only.
		List<String> tags = options.getContentTags();
		if (tags == null || tags.isEmpty()) {
			contentTags = Collections.singletonList("text");
		} else {
			List<String> lowered = new ArrayList<>();
			for (String t : tags) {
				lowered.add(t.trim().toLowerCase());
			}
			contentTags = lowered;
		}
	}

	public static void listFiles(String directoryName, List<File> files) {
		File directory = new File(directoryName);
		File[] fList = directory.listFiles();
		if (fList == null) {
			return;
		}
		for (File file : fList) {
			if (file.isFile()) {
				files.add(file);
			} else if (file.isDirectory()) {
				listFiles(file.getAbsolutePath(), files);
			}
		}
	}

	private void getNextScanner() throws IOException {
		if (fileIterator.hasNext()) {
			File nextFile = fileIterator.next();
			InputStream fileStream = new FileInputStream(nextFile);
			Reader decoder = new InputStreamReader(fileStream, "UTF-8");
			br = new BufferedReader(decoder);
		} else {
			br = null;
		}
	}

	/** Reads the next line, advancing across files; null when all files are exhausted. */
	private String readLine() throws IOException {
		while (br != null) {
			String line = br.readLine();
			if (line != null) {
				return line;
			}
			br.close();
			getNextScanner();
		}
		return null;
	}

	@Override
	public boolean hasNextDocument() {
		return br != null;
	}

	@Override
	public ParsedDocument getNextDocument() throws IOException, SAXException {
		while (br != null) {
			String line = readLine();
			if (line == null) {
				return null; // no more documents
			}
			if (!line.trim().equalsIgnoreCase("<DOC>")) {
				continue; // skip anything outside a document
			}
			// Accumulate the whole <DOC>…</DOC> block.
			StringBuilder block = new StringBuilder(line).append('\n');
			String inner;
			while ((inner = readLine()) != null) {
				block.append(inner).append('\n');
				if (inner.trim().toUpperCase().startsWith("</DOC>")) {
					break;
				}
			}
			ParsedDocument doc = buildDocument(block.toString());
			if (doc != null) {
				return doc;
			}
			// else: unparseable block — skip and continue to the next document
		}
		return null;
	}

	private ParsedDocument buildDocument(String block) {
		docNum++;
		try {
			// XML parser: treat the TREC tags generically (no HTML wrapping / tag-moving).
			Document xmlDoc = Jsoup.parse(block, "", Parser.xmlParser());

			String docno = firstTagText(xmlDoc, "docno");
			if (docno == null || docno.length() == 0) {
				docno = String.valueOf(docNum);
			}

			ParsedDocument doc = new ParsedDocument();
			doc.setDocumentFields(new ArrayList<ParsedDocumentField>());
			doc.addDocumentField(new ParsedDocumentField(EXTERNALID_FIELD, docno, false));
			doc.addDocumentField(new ParsedDocumentField(ID_FIELD, String.valueOf(docNum), false));

			// fulltext = markup-free text of the configured content tags, in document order.
			if (indexFullText) {
				doc.addDocumentField(new ParsedDocumentField(FULLTEXT_FIELD, gatherContent(xmlDoc), false));
			}

			// Optional named fields, only when requested; each markup-free.
			if (fieldsToIndex.contains(BODY_FIELD) || fieldsToIndex.contains(TEXT_FIELD)) {
				doc.addDocumentField(new ParsedDocumentField(BODY_FIELD, firstTagText(xmlDoc, "text"), false));
			}
			if (fieldsToIndex.contains(TITLE_FIELD)) {
				doc.addDocumentField(new ParsedDocumentField(TITLE_FIELD, firstTagText(xmlDoc, "title"), false));
			}
			if (fieldsToIndex.contains(HEADING_FIELD)) {
				doc.addDocumentField(new ParsedDocumentField(HEADING_FIELD, firstTagText(xmlDoc, "headline"), false));
			}
			if (fieldsToIndex.contains(URL_FIELD)) {
				doc.addDocumentField(new ParsedDocumentField(URL_FIELD, firstTagText(xmlDoc, "url"), false));
			}

			return doc;
		} catch (Exception e) {
			System.out.println("Could not parse document near: "
					+ block.substring(0, Math.min(80, block.length())).replace('\n', ' '));
			return null;
		}
	}

	/** Markup-free text of the first element with the given tag, or "" if absent. */
	private static String firstTagText(Document xmlDoc, String tag) {
		Elements els = xmlDoc.getElementsByTag(tag);
		if (els != null && els.size() > 0) {
			return els.get(0).text().trim();
		}
		return "";
	}

	/** Concatenates the markup-free text of every configured content tag, in document order. */
	private String gatherContent(Document xmlDoc) {
		Elements els = xmlDoc.select(String.join(", ", contentTags));
		StringJoiner sj = new StringJoiner(" ");
		for (Element el : els) {
			String text = el.text().trim();
			if (text.length() > 0) {
				sj.add(text);
			}
		}
		return sj.toString();
	}

}
