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
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.BytesRef;

public class IndriTermOpEnum extends IndriDocAndPostingsIterator {

	private IndriInvertedList invList;
	private Integer currentDocID;
	private List<IndriDocumentPosting> currentPostings;
	private int positionIndex;
	private int endPostion;

	public IndriTermOpEnum(IndriInvertedList invList) {
		this.invList = invList;
		currentDocID = -1;
		currentPostings = null;
		positionIndex = 0;
		endPostion = -1;
	}

	@Override
	public int docID() {
		return currentDocID.intValue();
	}

	@Override
	public int nextDoc() throws IOException {
		currentDocID = invList.getDocPostings().higherKey(currentDocID);
		if (currentDocID == null) {
			currentDocID = DocIdSetIterator.NO_MORE_DOCS;
		}
		loadCurrentPostings();
		return currentDocID;
	}

	@Override
	public int advance(int target) throws IOException {
		currentDocID = invList.getDocPostings().higherKey(target - 1);
		if (currentDocID == null) {
			currentDocID = DocIdSetIterator.NO_MORE_DOCS;
		}
		loadCurrentPostings();
		return currentDocID;
	}

	/**
	 * Loads the current document's postings in start-position order and resets the position
	 * cursor. Must be called whenever the current document changes.
	 */
	private void loadCurrentPostings() {
		positionIndex = 0;
		endPostion = -1;
		TreeMap<Integer, IndriDocumentPosting> docPostings = (currentDocID == null
				|| currentDocID.intValue() == DocIdSetIterator.NO_MORE_DOCS) ? null
						: invList.getDocPostings().get(currentDocID);
		currentPostings = (docPostings == null) ? null : new ArrayList<>(docPostings.values());
	}

	@Override
	public long cost() {
		return 0;
	}

	public int freq() throws IOException {
		return (currentPostings == null) ? 0 : currentPostings.size();
	}

	/**
	 * Returns the start position of the next posting (in start-position order), records its end
	 * position, and advances the position cursor. Returns -1 when the current document's postings
	 * are exhausted.
	 *
	 * <p>NOTE: the postings are stored in a {@code TreeMap} keyed by start position; earlier code
	 * indexed that map with the sequential cursor value (treating a 0,1,2,... counter as a
	 * start-position key), which returned -1 for any posting not starting at 0,1,2,... This
	 * iterates the postings in order instead.
	 */
	@Override
	public int nextPosition() throws IOException {
		if (currentPostings != null && positionIndex < currentPostings.size()) {
			IndriDocumentPosting posting = currentPostings.get(positionIndex);
			endPostion = posting.getEnd();
			positionIndex++;
			return posting.getStart();
		}
		return -1;
	}

	@Override
	public int endPosition() throws IOException {
		return endPostion;
	}

	@Override
	public int startOffset() throws IOException {
		return 0;
	}

	@Override
	public int endOffset() throws IOException {
		return 0;
	}

	@Override
	public BytesRef getPayload() throws IOException {
		return null;
	}

	public IndriInvertedList getInvList() {
		return invList;
	}

}
