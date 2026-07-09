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
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.lucene.analysis.Analyzer;
import org.lemurproject.lucindri.analyzer.EnglishAnalyzerConfigurable;
import org.lemurproject.lucindri.searcher.domain.JsonIndriQuery;
import org.lemurproject.lucindri.searcher.domain.JsonIndriQueryWrapper;
import org.lemurproject.lucindri.searcher.parser.QueryParseException;
import org.lemurproject.lucindri.searcher.service.LucindriSearchService;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

public class IndriSearch {

	public static void main(String[] args)
			throws Exception {
		if (args.length != 1) {
			System.out.println("Specify parameter file");
			System.exit(0);
		}

		String parametersFilePath = args[0];
		String queryParameters = readFile(parametersFilePath);

		JsonIndriQueryWrapper queryWrapper = null;
		if (isXML(queryParameters)) {
			queryWrapper = parseXML(queryParameters);
		}

		if (queryWrapper != null) {
			// Thin driver over the shared search core (TASK-0019): the reader/searcher/similarity/parser
			// pipeline that used to be inlined here now lives in LucindriSearchService, so the batch CLI and
			// the HTTP server run identical retrieval. TREC output format is unchanged.
			// maxPassages is inert here (batch never requests summaries), but keep it consistent with the
			// server default (4). Summary word-cap uses the service's default (TASK-0022).
			try (LucindriSearchService service = new LucindriSearchService(queryWrapper.getIndex(),
					queryWrapper.getRule(), queryWrapper.getStemmer(), queryWrapper.isRemoveStopwords(),
					queryWrapper.isIgnoreCase(), 4)) {
				for (JsonIndriQuery query : queryWrapper.getQueries()) {
					try {
						List<LucindriSearchService.SearchResult> results = service.search(query.getText(),
								queryWrapper.getCount(), false);
						int rank = 0;
						for (LucindriSearchService.SearchResult result : results) {
							rank++;
							System.out.println(String.join(" ", query.getNumber(), "Q0", result.docno,
									String.valueOf(rank), String.valueOf(result.score), "lucene"));
						}
					} catch (QueryParseException e) {
						// A malformed query must not abort the whole batch: report it and move on. (TASK-0015)
						System.err.println("Skipping malformed query " + query.getNumber() + ": " + e.getMessage());
					} catch (RuntimeException e) {
						// Defensive: an unexpected internal error on one query must not kill the run either.
						System.err.println("Internal error on query " + query.getNumber() + ": " + e);
					}
				}
			}
		} else {
			System.out.println("Could not parse query parameters.  Please provide XML or json query parameters.");
		}

	}

	private static boolean isXML(String text) {
		if (!text.startsWith("<")) {
			return false;
		}
		DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
		DocumentBuilder dBuilder;
		org.w3c.dom.Document doc = null;
		try {
			dBuilder = dbFactory.newDocumentBuilder();
			doc = dBuilder.parse(new InputSource(new StringReader(text)));
		} catch (ParserConfigurationException | SAXException | IOException e) {
			return false;
		}
		return true;
	}

	private static JsonIndriQueryWrapper parseXML(String text)
			throws ParserConfigurationException, SAXException, IOException {
		DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
		DocumentBuilder dBuilder;
		dBuilder = dbFactory.newDocumentBuilder();
		org.w3c.dom.Document doc = dBuilder.parse(new InputSource(new StringReader(text)));

		JsonIndriQueryWrapper queryWrapper = new JsonIndriQueryWrapper();
		String index = optText(doc, "index");
		if (index != null) {
			queryWrapper.setIndex(index);
		} else {
			System.out.println("Index must be defined in query parameters.");
			System.exit(0);
		}

		String count = optText(doc, "count");
		queryWrapper.setCount(count != null ? Integer.valueOf(count) : 100);

		String rule = optText(doc, "rule");
		if (rule != null) {
			queryWrapper.setRule(rule);
		}

		// Optional query-time analysis config; must match the index's analysis. Omitted -> historical
		// defaults (kstem, stopwords removed, lowercased).
		String stemmer = optText(doc, "stemmer");
		if (stemmer != null) {
			queryWrapper.setStemmer(stemmer);
		}
		String removeStopwords = optText(doc, "removeStopwords");
		if (removeStopwords != null) {
			queryWrapper.setRemoveStopwords(Boolean.valueOf(removeStopwords));
		}
		String ignoreCase = optText(doc, "ignoreCase");
		if (ignoreCase != null) {
			queryWrapper.setIgnoreCase(Boolean.valueOf(ignoreCase));
		}

		List<JsonIndriQuery> queries = new ArrayList<>();
		for (int i = 0; i < doc.getElementsByTagName("query").getLength(); i++) {
			NodeList childNodes = doc.getElementsByTagName("query").item(i).getChildNodes();
			JsonIndriQuery query = new JsonIndriQuery();
			for (int j = 0; j < childNodes.getLength(); j++) {
				Node childNode = childNodes.item(j);
				if (childNode.getNodeName().equals("number")) {
					query.setNumber(childNode.getTextContent());
				} else if (childNode.getNodeName().equals("text")) {
					query.setText(childNode.getTextContent());
				}
			}
			queries.add(query);
		}
		queryWrapper.setQueries(queries);
		return queryWrapper;
	}

	/** Text content of the first element with the given tag, or null if absent. */
	private static String optText(org.w3c.dom.Document doc, String tag) {
		NodeList nodes = doc.getElementsByTagName(tag);
		if (nodes != null && nodes.getLength() > 0 && nodes.item(0) != null) {
			return nodes.item(0).getTextContent();
		}
		return null;
	}

	private static String readFile(String filePath) {
		StringBuilder contentBuilder = new StringBuilder();
		try (Stream<String> stream = Files.lines(Paths.get(filePath), StandardCharsets.UTF_8)) {
			stream.forEach(s -> contentBuilder.append(s).append("\n"));
		} catch (IOException e) {
			e.printStackTrace();
		}
		return contentBuilder.toString();
	}

	public static Analyzer getConfigurableAnalyzer() {
		EnglishAnalyzerConfigurable an = new EnglishAnalyzerConfigurable();
		an.setLowercase(true);
		an.setStopwordRemoval(true);
		an.setStemmer(EnglishAnalyzerConfigurable.StemmerType.KSTEM);
		return an;
	}

}
