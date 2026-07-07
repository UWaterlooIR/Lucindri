/*
 * ===============================================================================================
 * Copyright (c) 2017 Carnegie Mellon University and University of Massachusetts. All Rights
 * Reserved.
 *
 * Use of the Lemur Toolkit for Language Modeling and Information Retrieval is subject to the terms
 * of the software license set forth in the LICENSE file included with this software, and also
 * available at http://www.lemurproject.org/license.html
 *
 * ================================================================================================
 */
package org.lemurproject.lucindri.indexer.documentwriter;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.search.similarities.LMDirichletSimilarity;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.lemurproject.lucindri.indexer.domain.IndexingConfiguration;
import org.lemurproject.lucindri.indexer.domain.ParsedDocument;
import org.lemurproject.lucindri.indexer.documentparser.DocumentParser;
import org.lemurproject.lucindri.indexer.domain.ParsedDocumentField;
import org.lemurproject.lucindri.indexer.factory.ConfigurableAnalyzerFactory;

public class LuceneDocumentWriter implements DocumentWriter {

	private static final Logger logger = Logger.getLogger(LuceneDocumentWriter.class.getName());

	/**
	 * Suffix of the per-doc NumericDocValues that holds an indexed text field's exact token count, used
	 * by {@code exactDocumentLength} indexing (TASK-0012). The searcher reads the same "<field>_len" name
	 * (kept in sync in {@code IndriLengthSource} in the LucindriSearcher module, which cannot depend on
	 * this module).
	 */
	public static final String EXACT_LENGTH_SUFFIX = "_len";

	private Analyzer analyzer;
	private IndexWriter iWriter;
	private FieldType fieldType;
	private Similarity similarity;
	private final boolean exactDocumentLength;

	private List<Document> luceneDocs;
	private Field luceneField;
	private Document luceneDoc;

	public LuceneDocumentWriter(IndexingConfiguration options)
			throws IOException, ClassCastException, ClassNotFoundException {
		ConfigurableAnalyzerFactory analyzerFactory = new ConfigurableAnalyzerFactory();
		analyzer = analyzerFactory.getConfigurableAnalyzer(options);
		this.similarity = new LMDirichletSimilarity();
		this.exactDocumentLength = options.isExactDocumentLength();

		String indexDirectory = Paths.get(options.getIndexDirectory(), options.getIndexName()).toString();
		iWriter = createIndexWriter(indexDirectory, analyzer);

		fieldType = getFieldType();

		// luceneDoc = new Document();
		luceneDocs = new ArrayList<>();
	}

	/**
	 * Creates the Lucene IndexWriter for writing document to the index.
	 * 
	 * @param indexDirectory
	 * @param docParser
	 * @param analyzer
	 * @return
	 * @throws IOException
	 */
	private IndexWriter createIndexWriter(String indexDirectory, Analyzer analyzer) throws IOException {

		Path path = Paths.get(indexDirectory);
		Directory directory = FSDirectory.open(path);
		// Directory directory = new SimpleFSDirectory(path, NoLockFactory.INSTANCE);

		IndexWriterConfig config = new IndexWriterConfig(analyzer);
		config.setOpenMode(OpenMode.CREATE);
		config.setSimilarity(similarity);
		config.setUseCompoundFile(false);
		IndexWriter iwriter = new IndexWriter(directory, config);

		return iwriter;
	}

	/**
	 * Defines how fields are stored in Lucene.
	 * 
	 * @return
	 */
	private FieldType getFieldType() {
		logger.log(Level.FINE, "Enter");
		FieldType fieldType = new FieldType();
		fieldType.setTokenized(true);
		fieldType.setStored(true);
		fieldType.setIndexOptions(org.apache.lucene.index.IndexOptions.DOCS_AND_FREQS_AND_POSITIONS);
		fieldType.setStoreTermVectors(false);
		fieldType.setStoreTermVectorPositions(false);
		fieldType.setStoreTermVectorOffsets(false);
		logger.log(Level.FINE, "Exit");
		return fieldType;
	}

	public void writeDocuments(ParsedDocument parsedDoc) throws IOException {
		if (parsedDoc != null) {
			luceneDoc = new Document();

			// When exactDocumentLength is on, accumulate each text field's token count across all of its
			// values (a field may appear more than once = multi-valued, and the norm aggregates them). Only
			// ONE NumericDocValues per field per doc is legal, so we add the "<field>_len" values once, after
			// the field loop. LinkedHashMap keeps output order stable/deterministic. TASK-0012.
			Map<String, Long> fieldLengths = exactDocumentLength ? new LinkedHashMap<>() : null;

			// Add document to search engine
			for (ParsedDocumentField docField : parsedDoc.getDocumentFields()) {
				if (docField.getContent() != null) {
					if (DocumentParser.EXTERNALID_FIELD.equals(docField.getFieldName())) {
						// The docno is metadata, not searchable text: index it as a single, non-analyzed
						// keyword term (StringField) so an exact TermQuery can look a document up by docno,
						// and store it (the searcher/TREC output reads the stored value). It gets NO norms,
						// positions, or exactDocumentLength "<field>_len" -- it is not part of Indri queries,
						// which are all about the tokenized text (fulltext). (TASK-0020)
						luceneDoc.add(new StringField(docField.getFieldName(), docField.getContent(),
								Field.Store.YES));
					} else if (!docField.isNumeric()) {
						luceneField = new Field(docField.getFieldName(), docField.getContent(), fieldType);
						luceneDoc.add(luceneField);
						if (fieldLengths != null) {
							long count = countTokens(docField.getFieldName(), docField.getContent());
							fieldLengths.merge(docField.getFieldName(), count, Long::sum);
						}
					} else {
						luceneDoc.add(new NumericDocValuesField(docField.getFieldName(),
								Long.valueOf(docField.getContent()).longValue()));
					}
				}
			}
			if (fieldLengths != null) {
				for (Map.Entry<String, Long> e : fieldLengths.entrySet()) {
					luceneDoc.add(new NumericDocValuesField(e.getKey() + EXACT_LENGTH_SUFFIX, e.getValue()));
				}
			}
			// iWriter.addDocument(luceneDoc);
			luceneDocs.add(luceneDoc);

		}
		if (luceneDocs.size() >= 500) {
			iWriter.addDocuments(luceneDocs);
			luceneDocs = new ArrayList<>();
		}
	}

	/**
	 * Counts the tokens the index analyzer emits for {@code content} in {@code fieldName}, matching the
	 * document length Lucene encodes in the norm: the number of emitted tokens whose position increment is
	 * &gt; 0. This equals {@code FieldInvertState.getLength() - getNumOverlap()} — removed stopwords are
	 * not emitted (they don't count); synonym/overlap tokens (posIncr 0) don't count either. So in
	 * SmallFloat's lossless range this equals the norm-decoded length, and for long docs it is the exact,
	 * un-quantized length. This is {@code numTerms} with <b>no</b> {@code +1} (= Indri's {@code |d|}).
	 * TASK-0012.
	 */
	private long countTokens(String fieldName, String content) throws IOException {
		long count = 0;
		try (TokenStream ts = analyzer.tokenStream(fieldName, content)) {
			PositionIncrementAttribute posIncr = ts.addAttribute(PositionIncrementAttribute.class);
			ts.reset();
			while (ts.incrementToken()) {
				if (posIncr.getPositionIncrement() > 0) {
					count++;
				}
			}
			ts.end();
		}
		return count;
	}

	public void closeDocumentWriter() throws IOException {
		if (luceneDocs.size() > 0) {
			iWriter.addDocuments(luceneDocs);
		}
		// writeTotalDocLens();
		iWriter.close();
	}

//	private void writeTotalDocLens() throws IOException {
//		Map<String, Long> docLens = ((IndriDirichletSimilarity) similarity).getTotalFieldLengths();
//		Document docLenDoc = new Document();
//		Field nameField = new Field(IndriConstants.COLLECTION_TOTAL_DOCUMENT_NAME,
//				IndriConstants.COLLECTION_TOTAL_DOCUMENT_NAME, fieldType);
//		docLens.forEach((fieldName, length) -> {
//			Field field = new NumericDocValuesField(fieldName + IndriConstants.FIELD_TOTAL_SUFFIX, length);
//			docLenDoc.add(field);
//		});
//		iWriter.addDocument(docLenDoc);
//	}

}
