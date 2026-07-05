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

import java.util.TreeMap;

public class IndriInvertedList {

	private final String field;
	private int ctf;
	private int df;
	private TreeMap<Integer, TreeMap<Integer, IndriDocumentPosting>> docPostings;

	public IndriInvertedList(String field) {
		this.field = field;
		docPostings = new TreeMap<>();
	}

	public String getField() {
		return field;
	}

	public int getCtf() {
		return ctf;
	}

	public void setCtf(int ctf) {
		this.ctf = ctf;
	}

	public int getDf() {
		return df;
	}

	public void setDf(int df) {
		this.df = df;
	}

	public TreeMap<Integer, TreeMap<Integer, IndriDocumentPosting>> getDocPostings() {
		return docPostings;
	}

	public void setDocPostings(TreeMap<Integer, TreeMap<Integer, IndriDocumentPosting>> docPostings) {
		this.docPostings = docPostings;
	}

	public void addPosting(Integer docID, Integer startLocation, Integer endLocation) {
		docPostings.putIfAbsent(docID, new TreeMap<>());
		// Check if a posting that includes this start and end position already exist
		TreeMap<Integer, IndriDocumentPosting> postings = docPostings.get(docID);
		boolean addPosting = true;
		for (IndriDocumentPosting posting : postings.values()) {
			if (startLocation >= posting.getStart() && endLocation <= posting.getEnd()) {
				addPosting = false;
			}
		}
		if (addPosting) {
			IndriDocumentPosting posting = new IndriDocumentPosting(startLocation, endLocation);
			docPostings.get(docID).put(startLocation, posting);
		}
	}

	// NOTE: this list only ever holds ONE segment's postings (it is built per leaf in
	// IndriTermOpWeight#getScorer), so it must NOT be used to derive collection statistics for a derived
	// proximity/synonym term. Collection-wide cf/df are aggregated across all leaves in
	// IndriTermOpWeight#ensureCollectionStats. (A former getTermStatistics() here returned segment-local
	// stats, which corrupted the smoothing background of an absent term-op in a belief combination.)

}
