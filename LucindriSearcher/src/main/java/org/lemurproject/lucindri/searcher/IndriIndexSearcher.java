/*
 * ===============================================================================================
 * Copyright (c) 2020 Carnegie Mellon University and University of Massachusetts. All Rights
 * Reserved.
 *
 * Use of the Lemur Toolkit for Language Modeling and Information Retrieval is subject to the terms
 * of the software license set forth in the LICENSE file included with this software, and also
 * available at http://www.lemurproject.org/license.html
 *
 * ================================================================================================
 */
package org.lemurproject.lucindri.searcher;

import java.io.IOException;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Terms;
import org.apache.lucene.search.CollectionStatistics;
import org.apache.lucene.search.IndexSearcher;

public class IndriIndexSearcher extends IndexSearcher {

	private CollectionStatistics collectionStatistics;

	public IndriIndexSearcher(IndexReader r) {
		super(r);
	}

	@Override
	public CollectionStatistics collectionStatistics(String field) throws IOException {
		if (collectionStatistics == null) {
			long docCount = 0;
			long sumTotalTermFreq = 0;
			long sumDocFreq = 0;
			for (LeafReaderContext leaf : getIndexReader().leaves()) {
				final Terms terms = leaf.reader().terms(field);
				if (terms == null) {
					continue;
				}
				docCount += terms.getDocCount();
				sumTotalTermFreq += terms.getSumTotalTermFreq();
				sumDocFreq += terms.getSumDocFreq();
			}
			if (docCount == 0) {
				return null;
			}
			collectionStatistics = new CollectionStatistics(field, getIndexReader().maxDoc(), docCount,
					sumTotalTermFreq, sumDocFreq);
		}
		return collectionStatistics;
	}

}
