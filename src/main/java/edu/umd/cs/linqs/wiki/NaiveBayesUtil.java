package edu.umd.cs.linqs.wiki; 

import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.umd.cs.psl.database.loading.Inserter;


/**
 * This class takes a set of unique keys, reads a file written in sparse format 
 * of word counts (or tf-idf scores) and category labels and learns a Naive Bayes 
 * classifier for the labels.
 * 
 * @author Bert Huang <bert@cs.umd.edu>
 *
 */
public class NaiveBayesUtil {

	private Logger log;
	private Map<Integer, Map<Integer, Double>> wordProbs;
	private Map<Integer, Double> labelPriors;
	private Map<Integer, Double> classConstants;
	private Set<Integer> allWords;

	private final int prior = 1;
	private final int catPrior = 1;

	public NaiveBayesUtil() {
		log = LoggerFactory.getLogger("NaiveBayesUtility");
		labelPriors = new HashMap<Integer, Double>();
		classConstants = new HashMap<Integer, Double>();
		wordProbs = new HashMap<Integer, Map<Integer, Double>>();
		allWords = new HashSet<Integer>();
	}


	/**
	 * Loads word count data and labels
	 * @param trainingKeys set of training unique keys
	 */
	public void learn(Set<Integer> trainingKeys, String categoryFile, String wordFile) {
		try {
			Scanner catScanner = new Scanner(new FileReader(categoryFile));

			CounterMap<Integer> catTotals = new CounterMap<Integer>();

			Map<Integer, Integer> categories = new HashMap<Integer, Integer>();

			int documentCount = 0;

			while (catScanner.hasNext()) {
				String line = catScanner.nextLine();
				String [] tokens = line.split("\t");
				if (tokens.length == 2) {
					Integer docID = Integer.decode(tokens[0]);
					Integer label = Integer.decode(tokens[1]);

					if (trainingKeys.contains(docID)) {
						categories.put(docID, label);

						catTotals.increment(label);
						documentCount++;
					}
				}
			}
			catScanner.close();

			// initialize category counter
			Map<Integer, CounterMap<Integer>> catCounts = new HashMap<Integer, CounterMap<Integer>>();
			for (Integer cat : catTotals.keySet())  {
				labelPriors.put(cat, Math.log(catTotals.get(cat) + catPrior) - Math.log(documentCount)); 
				catCounts.put(cat, new CounterMap<Integer>());
			}

			log.debug("Label priors: " + labelPriors);

			// load words
			log.debug("Starting word counts");
			Scanner wordScanner = new Scanner(new FileReader(wordFile));
			while (wordScanner.hasNext()) {
				String line = wordScanner.nextLine();
				String [] tokens = line.split("\t");
				Integer docID = Integer.decode(tokens[0]);
				Set<Integer> docWords = parseWords(tokens[1]);
				for (Integer wordID : docWords) {
					if (trainingKeys.contains(docID)) {
						Integer cat = categories.get(docID);
						catCounts.get(cat).increment(wordID);
						//log.debug("Document {} in category {} had word " + wordID, docID, cat);
					}
					allWords.add(wordID);
				}
			}
			wordScanner.close();

			// convert counts to log likelihoods
			log.debug("Computing log-likelihoods from counts");
			for (Integer cat : catTotals.keySet()) {
				HashMap<Integer, Double> probs = new HashMap<Integer, Double>();
				double classConstant = 0.0;
				for (Integer word : allWords) {
					int count = catCounts.get(cat).get(word);
					double denominator = Math.log(2*prior + catTotals.get(cat)); 
					double logp = Math.log(prior + count) - 
							Math.log(prior + catTotals.get(cat) - count);
					classConstant += Math.log(prior + catTotals.get(cat) - count) - denominator;
					probs.put(word, logp);
					
					//log.debug("Word " + word + " appeared in " + count + " documents of cat " + cat + ", which had " + catTotals.get(cat) + " total docs");
					//log.debug("Estimated p(word | cat) {}", (double) (prior + count) / (double) (prior + catTotals.get(cat)));
				}
				wordProbs.put(cat, probs);

				classConstants.put(cat, classConstant);

				catCounts.get(cat).clear();
			}

			log.debug("Finished word counts");



			if (log.isDebugEnabled()) {
				// evaluate training accuracy
				int correct = 0;
				int total = 0;
				wordScanner = new Scanner(new FileReader(wordFile));
				while (wordScanner.hasNext()) {
					String line = wordScanner.nextLine();
					String [] tokens = line.split("\t");
					Integer docID = Integer.decode(tokens[0]);
					if (categories.containsKey(docID)) {
						Integer cat = categories.get(docID);
						Set<Integer> docWords = parseWords(tokens[1]);
						Map<Integer, Double> prediction = predict(docWords);
						Integer pred = -1;
						//log.debug(prediction.toString());
						for (Integer c : catCounts.keySet())
							if (pred == -1 || prediction.get(c) > prediction.get(pred))
								pred = c;
						//log.debug("Predicted {}, true {}", pred, cat);
						if (cat.equals(pred))
							correct++;
						total++;
					}
				}
				wordScanner.close();

				log.debug("Naive Bayes training accuracy: {}", (double) correct / (double) total);
			}




			catCounts.clear();
			catTotals.clear();
			categories.clear();
		} catch (IOException e) {
			log.error(e.toString());
		}
	}

	/**
	 * Takes a map of unnormalized log-likelihoods and normalizes them in a 
	 * numerically stable way
	 * @param scores
	 */
	private void convertToProbability(Map<Integer, Double> scores) {
		double max = Double.NEGATIVE_INFINITY;
		for (Double score : scores.values())
			max = Math.max(max,  score);
		double normalizer = 0;
		for (Double score : scores.values())
			normalizer += Math.exp(score - max);
		for (Map.Entry<Integer, Double> e : scores.entrySet()) 
			scores.put(e.getKey(), Math.exp(e.getValue() - max - Math.log(normalizer)));
	}

	private Set<Integer> parseWords(String string) {
		String [] tokens = string.split(" ");
		Set<Integer> words = new HashSet<Integer>(1000);
		for (int i = 0; i < tokens.length; i++) {
			if (tokens[i].length() > 1) {
				String [] subTokens = tokens[i].split(":");
				words.add(Integer.decode(subTokens[0]));
			}
		}
		return words;
	}

	/**
	 * returns a map of the probabilities for each category
	 * @param docWords word ids contained in this file
	 * @return map of probabilities for each category
	 */
	public Map<Integer, Double> predict(Set<Integer> docWords) {
		Map<Integer, Double> scores = new HashMap<Integer, Double>();
		for (Integer cat : labelPriors.keySet()) {
			// TODO: zeroed out label priors
			double score = 0*labelPriors.get(cat) + classConstants.get(cat);
			Map<Integer, Double> probs = wordProbs.get(cat);
			for (Integer word : docWords)
				if (allWords.contains(word))
					score += probs.get(word);

			//log.trace("Document scores " + score + " for label " + cat);
			scores.put(cat, score);
		}
		convertToProbability(scores);
		return scores;
	}
	
	/**
	 * Predcits the maximum likelihood assignment 
	 * @param docWords set of observed words
	 * @return category with maximum likelihood
	 */
	public Integer predictBest(Set<Integer> docWords) {
		Map<Integer, Double> scores = predict(docWords);
		double bestScore = Double.NEGATIVE_INFINITY;
		Integer bestCat = 0;
		for (Map.Entry<Integer, Double> e : scores.entrySet()) {
			if (e.getValue() > bestScore) {
				bestScore = e.getValue();
				bestCat = e.getKey();
			}
		}
		return bestCat;
	}

	/**
	 * reads in a file of word counts or tf-idf scores and generates the 
	 * predicted label for each document
	 * @param wordFile filename of document containing word counts
	 * @return map between document ID and predicted label
	 */
	public Map<Integer, Map<Integer, Double>> predictAll(String wordFile, Set<Integer> documents) {
		Map<Integer, Map<Integer, Double>> predictions = new HashMap<Integer, Map<Integer, Double>>();
		// load words
		try {
			log.trace("Starting to load file for prediction");
			Scanner wordScanner = new Scanner(new FileReader(wordFile));
			while (wordScanner.hasNext()) {
				String line = wordScanner.nextLine();
				String [] tokens = line.split("\t");
				Integer docID = Integer.decode(tokens[0]);
				if (documents.contains(docID)) {
					Set<Integer> docWords = parseWords(tokens[1]);

					predictions.put(docID, this.predict(docWords));
					docWords.clear();
				}
				//log.trace("Document " + docID + ", prediction: " + predictions.get(docID));
			}
			wordScanner.close();
			log.trace("Finished prediction on full file");
		} catch (IOException e) {
			log.error(e.toString());
		}
		return predictions;
	}

	/**
	 * reads in a file of word counts or tf-idf scores and generates the 
	 * probability for each label of each document
	 * @param wordFile filename of document containing word counts
	 * @return map between document ID and predicted label
	 */
	public void insertAllProbabilities(String wordFile, Set<Integer> documents, Inserter inserter) {
		// load words
		try {
			log.debug("Loading file for Naive Bayes prediction");
			Scanner wordScanner = new Scanner(new FileReader(wordFile));
			while (wordScanner.hasNext()) {
				String line = wordScanner.nextLine();
				String [] tokens = line.split("\t");
				Integer docID = Integer.decode(tokens[0]);
				if (documents.contains(docID)) {
					Set<Integer> docWords = parseWords(tokens[1]);

					Map<Integer, Double> probs = this.predict(docWords);

					for (Map.Entry<Integer, Double> e : probs.entrySet()) {
						inserter.insertValue(e.getValue(), docID, e.getKey());
						log.trace("NB predicts p={} for {}", e.getValue(), e.getKey());
					}
				}
			}
			wordScanner.close();
			log.trace("Finished prediction on full file");
		} catch (IOException e) {
			log.error(e.toString());
		}
	}
	
	/**
	 * reads in a file of word counts or tf-idf scores and generates the 
	 * predicted label for each document
	 * @param wordFile filename of document containing word counts
	 * @return map between document ID and predicted label
	 */
	public void insertAllPredictions(String wordFile, Set<Integer> documents, Inserter inserter) {
		// load words
		try {
			log.debug("Loading file for Naive Bayes prediction");
			Scanner wordScanner = new Scanner(new FileReader(wordFile));
			while (wordScanner.hasNext()) {
				String line = wordScanner.nextLine();
				String [] tokens = line.split("\t");
				Integer docID = Integer.decode(tokens[0]);
				if (documents.contains(docID)) {
					Set<Integer> docWords = parseWords(tokens[1]);

					Integer cat = this.predictBest(docWords);
					
					inserter.insertValue(1.0, docID, cat);
				}
			}
			wordScanner.close();
			log.trace("Finished prediction on full file");
		} catch (IOException e) {
			log.error(e.toString());
		}
	}


	/**
	 * detaches all data structures
	 */
	public void close() {
		for (Integer key : labelPriors.keySet()) {
			wordProbs.get(key).clear();
		}
		wordProbs.clear();
		labelPriors.clear();
		allWords.clear();
	}

	/**
	 * small test function
	 * @param args
	 */
	public static void main(String [] args) {
		String documents = "data/pruned-document.txt";
		String labels = "data/newCategoryBelonging.txt";

		NaiveBayesUtil nb = new NaiveBayesUtil();

		HashSet<Integer> trainingKeys = new HashSet<Integer>();
		for (int i = 0; i <= 3000; i++) {
			trainingKeys.add(i);
		}

		nb.learn(trainingKeys, labels, documents);

		Map<Integer, Map<Integer, Double>> allPredictions = nb.predictAll(documents, trainingKeys);

		try {
			Scanner catScanner = new Scanner(new FileReader(labels));

			Map<Integer, Integer> categories = new HashMap<Integer, Integer>();

			while (catScanner.hasNext()) {
				String line = catScanner.nextLine();
				String [] tokens = line.split("\t");
				if (tokens.length == 2) {
					Integer docID = Integer.decode(tokens[0]);
					Integer label = Integer.decode(tokens[1]);

					categories.put(docID, label);
				}
			}
			catScanner.close();

			double avg = 0;

			for (Integer i : allPredictions.keySet()) {
				System.out.println("Document " + i + ", true " + categories.get(i) + 
						", predicted " + allPredictions.get(i).get(categories.get(i)));
				avg += allPredictions.get(i).get(categories.get(i));
			}
			System.out.println("Avg probability of correct label " + avg / (double) allPredictions.keySet().size());
			System.out.println("Baseline " + 1.0 / 19.0);

		} catch (IOException e) {
			e.printStackTrace();
		}

		nb.close();
	}

	
	private class CounterMap<T> {
		public CounterMap() {
			counts = new HashMap<T,Integer>();
		}

		public void increment(T key) {
			int c = 0;
			if (counts.containsKey(key))
				c = counts.get(key);
			counts.put(key, c+1);
		}

		public int get(T key) {
			if (counts.containsKey(key))
				return counts.get(key).intValue();
			return 0;
		}

		public void clear() { counts.clear(); };
		public Set<T> keySet() { return counts.keySet(); }

		private Map<T,Integer> counts;

	}
}
