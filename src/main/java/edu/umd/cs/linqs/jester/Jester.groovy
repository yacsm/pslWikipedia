package edu.umd.cs.linqs.jester

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import com.google.common.collect.Iterables

import edu.umd.cs.linqs.WeightLearner
import edu.umd.cs.psl.application.inference.MPEInference
import edu.umd.cs.psl.config.*
import edu.umd.cs.psl.database.DataStore
import edu.umd.cs.psl.database.Database
import edu.umd.cs.psl.database.DatabasePopulator
import edu.umd.cs.psl.database.Partition
import edu.umd.cs.psl.database.ResultList
import edu.umd.cs.psl.database.rdbms.RDBMSDataStore
import edu.umd.cs.psl.database.rdbms.driver.H2DatabaseDriver
import edu.umd.cs.psl.database.rdbms.driver.H2DatabaseDriver.Type
import edu.umd.cs.psl.evaluation.result.FullInferenceResult
import edu.umd.cs.psl.evaluation.statistics.ContinuousPredictionComparator
import edu.umd.cs.psl.groovy.*
import edu.umd.cs.psl.model.argument.ArgumentType
import edu.umd.cs.psl.model.argument.GroundTerm
import edu.umd.cs.psl.model.argument.UniqueID
import edu.umd.cs.psl.model.argument.Variable
import edu.umd.cs.psl.model.atom.GroundAtom
import edu.umd.cs.psl.model.atom.QueryAtom
import edu.umd.cs.psl.model.atom.RandomVariableAtom
import edu.umd.cs.psl.model.kernel.CompatibilityKernel
import edu.umd.cs.psl.model.parameters.Weight
import edu.umd.cs.psl.ui.loading.*
import edu.umd.cs.psl.util.database.Queries


/*** CONFIGURATION PARAMETERS ***/

dataPath = "./data/jester/"
methods = ["MLE","MPLE"]

Logger log = LoggerFactory.getLogger(this.class)
ConfigManager cm = ConfigManager.getManager();
ConfigBundle cb = cm.getBundle("jester");

defPath = System.getProperty("java.io.tmpdir") + "/jester"
def dbpath = cb.getString("dbpath", defPath)
DataStore data = new RDBMSDataStore(new H2DatabaseDriver(Type.Disk, dbpath, true), cb)
folds = 1

/*** MODEL DEFINITION ***/

System.out.println("Initializing model ...");

PSLModel m = new PSLModel(this, data);

/* PREDICATES */

m.add predicate: "user", types: [ArgumentType.UniqueID];
m.add predicate: "joke", types: [ArgumentType.UniqueID];
m.add predicate: "rating", types: [ArgumentType.UniqueID,ArgumentType.UniqueID];
m.add predicate: "ratingObs", types: [ArgumentType.UniqueID,ArgumentType.UniqueID];
m.add predicate: "ratingPrior", types: [ArgumentType.UniqueID];
//m.add predicate: "jokeText", types: [ArgumentType.UniqueID,ArgumentType.String];
m.add predicate: "avgUserRatingObs", types: [ArgumentType.UniqueID];
m.add predicate: "avgJokeRatingObs", types: [ArgumentType.UniqueID];
m.add predicate: "simObsTaste", types: [ArgumentType.UniqueID,ArgumentType.UniqueID];
m.add predicate: "simObsRating", types: [ArgumentType.UniqueID,ArgumentType.UniqueID];
m.add predicate: "simJokeText", types: [ArgumentType.UniqueID,ArgumentType.UniqueID];

/* RULES */

def sq = cb.getBoolean("squared", true);

// Ratings should concentrate around average (per user, item)
//m.add rule: avgUserRatingObs(U) >> avgUserRating(U), weight: 1.0, squared: sq;
//m.add rule: avgUserRating(U) >> avgUserRatingObs(U), weight: 1.0, squared: sq;
//m.add rule: avgJokeRatingObs(J) >> avgJokeRating(J), weight: 1.0, squared: sq;
//m.add rule: avgJokeRating(J) >> avgJokeRatingObs(J), weight: 1.0, squared: sq;

// If U1,U2 have similar taste, then they will rate J similarly
//m.add rule: ( user(U1) & user(U2) & simObsTaste(U1,U2) & rating(U1,J) ) >> rating(U2,J), weight: 1.0, squared: sq;

// If J1,J2 have similar ratings, then U will rate them similarly
m.add rule: ( joke(J1) & joke(J2) & simObsRating(J1,J2) & rating(U,J1) ) >> rating(U,J2), weight: 1.0, squared: sq;

// If J1,J2 have similar text, then U will rate them similarly
//m.add rule: ( jokeText(J1,T1) & jokeText(J2,T2) & simJokeText(T1,T2) & rating(U,J1) ) >> rating(U,J2), weight: 1.0, squared: sq;
m.add rule: ( simJokeText(J1,J2) & rating(U,J1) ) >> rating(U,J2), weight: 1.0, squared: sq;

// Ratings should concentrate around observed user/joke averages
m.add rule: ( user(U) & joke(J) & avgUserRatingObs(U) ) >> rating(U,J), weight: 1.0, squared: sq;
m.add rule: ( user(U) & joke(J) & avgJokeRatingObs(J) ) >> rating(U,J), weight: 1.0, squared: sq;
m.add rule: ( user(U) & joke(J) & rating(U,J) ) >> avgUserRatingObs(U), weight: 1.0, squared: sq;
m.add rule: ( user(U) & joke(J) & rating(U,J) ) >> avgJokeRatingObs(J), weight: 1.0, squared: sq;

// Two-sided prior
UniqueID constant = data.getUniqueID(0)
m.add rule: ( user(U) & joke(J) & ratingPrior(constant) ) >> rating(U,J), weight: 1.0, squared: sq;
m.add rule: ( rating(U,J) ) >> ratingPrior(constant), weight: 1.0, squared: sq;

System.out.println(m)

/* get all default weights */
Map<CompatibilityKernel,Weight> initWeights = new HashMap<CompatibilityKernel, Weight>()
for (CompatibilityKernel k : Iterables.filter(m.getKernels(), CompatibilityKernel.class))
	initWeights.put(k, k.getWeight());


/*** LOAD DATA ***/

System.out.println("Loading data ...");

ArrayList<Double> scores = new ArrayList<Double>(folds);

for (int fold = 0; fold < folds; fold++) {

	Partition read_tr = new Partition(0 + fold * folds);
	Partition write_tr = new Partition(1 + fold * folds);
	Partition read_te = new Partition(2 + fold * folds);
	Partition write_te = new Partition(3 + fold * folds);
	Partition labels_tr = new Partition(4 + fold * folds);
	Partition labels_te = new Partition(5 + fold * folds);

	def inserter;

	// users
	inserter = data.getInserter(user, read_tr);
	InserterUtils.loadDelimitedData(inserter, dataPath + "/users-tr-sm.txt");
	inserter = data.getInserter(user, read_te);
	InserterUtils.loadDelimitedData(inserter, dataPath + "/users-te-sm.txt");
	// jokes
	inserter = data.getInserter(joke, read_tr);
	InserterUtils.loadDelimitedData(inserter, dataPath + "/jokes.txt");
	inserter = data.getInserter(joke, read_te);
	InserterUtils.loadDelimitedData(inserter, dataPath + "/jokes.txt");
	// joke text
//	inserter = data.getInserter(jokeText, read_tr);
//	InserterUtils.loadDelimitedData(inserter, dataPath + "/joketext/joketext.txt");
//	inserter = data.getInserter(jokeText, read_te);
//	InserterUtils.loadDelimitedData(inserter, dataPath + "/joketext/joketext.txt");
	// joke text similarity
	inserter = data.getInserter(simJokeText, read_tr);
	InserterUtils.loadDelimitedDataTruth(inserter, dataPath + "/joketext/jokeTextSim.txt");
	inserter = data.getInserter(simJokeText, read_te);
	InserterUtils.loadDelimitedDataTruth(inserter, dataPath + "/joketext/jokeTextSim.txt");
	// observed ratings
	inserter = data.getInserter(rating, read_tr);
	InserterUtils.loadDelimitedDataTruth(inserter, dataPath + "/ratings/jester-1-tr-obs-" + fold + ".txt");
	inserter = data.getInserter(ratingObs, read_tr);
	InserterUtils.loadDelimitedDataTruth(inserter, dataPath + "/ratings/jester-1-tr-obs-" + fold + ".txt");
	inserter = data.getInserter(rating, read_te);
	InserterUtils.loadDelimitedDataTruth(inserter, dataPath + "/ratings/jester-1-te-obs-" + fold + ".txt");
	inserter = data.getInserter(ratingObs, read_te);
	InserterUtils.loadDelimitedDataTruth(inserter, dataPath + "/ratings/jester-1-te-obs-" + fold + ".txt");
	// unobserved ratings (ground truth)
	inserter = data.getInserter(rating, labels_tr);
	InserterUtils.loadDelimitedDataTruth(inserter, dataPath + "/ratings/jester-1-tr-uno-" + fold + ".txt");
	inserter = data.getInserter(rating, labels_te);
	InserterUtils.loadDelimitedDataTruth(inserter, dataPath + "/ratings/jester-1-te-uno-" + fold + ".txt");
	// prior (we'll overwrite later)
	data.getInserter(ratingPrior, read_tr).insertValue(0.5, constant)
	data.getInserter(ratingPrior, read_te).insertValue(0.5, constant)
	

	/** POPULATE DB ***/

	/* We want to populate the database with all groundings 'rating' and 'ratingObs'
	 * To do so, we will query for all users and jokes in train/test, then use the
	 * database populator to compute the cross-product. 
	 */
	DatabasePopulator dbPop;
	Variable User = new Variable("User");
	Variable Joke = new Variable("Joke");
	Set<GroundTerm> users = new HashSet<GroundTerm>();
	Set<GroundTerm> jokes = new HashSet<GroundTerm>();
	Map<Variable, Set<GroundTerm>> subs = new HashMap<Variable, Set<GroundTerm>>();
	subs.put(User, users);
	subs.put(Joke, jokes);
	ResultList results;
	def toClose;
	ProjectionAverage userAverager = new ProjectionAverage(ratingObs, 1);
	ProjectionAverage jokeAverager = new ProjectionAverage(ratingObs, 0);
	AdjCosineSimilarity userCosSim = new AdjCosineSimilarity(ratingObs, 1, avgJokeRatingObs);
	AdjCosineSimilarity jokeCosSim = new AdjCosineSimilarity(ratingObs, 0, avgUserRatingObs);

	/* First we populate training database.
	 * In the process, we will precompute averages ratings. 
	 */
	System.out.println("Computing averages ...")
	Database trainDB = data.getDatabase(read_tr);
	results = trainDB.executeQuery(Queries.getQueryForAllAtoms(user));
	for (int i = 0; i < results.size(); i++) {
		GroundTerm u = results.get(i)[0];
		users.add(u);
		double avg = userAverager.getValue(trainDB, u);
		RandomVariableAtom a = (RandomVariableAtom) trainDB.getAtom(avgUserRatingObs, u);
		a.setValue(avg);
		trainDB.commit(a);
	}
	results = trainDB.executeQuery(Queries.getQueryForAllAtoms(joke));
	for (int i = 0; i < results.size(); i++) {
		GroundTerm j = results.get(i)[0];
		jokes.add(j);
		double avg = jokeAverager.getValue(trainDB, j);
		RandomVariableAtom a = (RandomVariableAtom) trainDB.getAtom(avgJokeRatingObs, j);
		a.setValue(avg);
		trainDB.commit(a);
	}
	/* Compute the prior as average over all observed ratings.
	 * (This is not the most efficient way of doing this. We should be able to 
	 * compute the average overall rating when we compute user/item averages.)
	 */
	double avgAllRatingObs = 0.0;
	Set<GroundAtom> allRatingObs = Queries.getAllAtoms(trainDB, ratingObs);
	for (GroundAtom a : allRatingObs) {
		avgAllRatingObs += a.getValue();
	}
	avgAllRatingObs /= allRatingObs.size();
	System.out.println("  Average rating (train): " + avgAllRatingObs);
	RandomVariableAtom priorAtom = (RandomVariableAtom) trainDB.getAtom(ratingPrior, constant);
	priorAtom.setValue(avgAllRatingObs);
	
	/* Precompute the similarities. */
	System.out.println("Computing training similarities ...")
	double avgsim = 0.0;
	for (GroundTerm j1 : jokes) {
		for (GroundTerm j2 : jokes) {
			double s = jokeCosSim.getValue(trainDB, j1, j2);
			RandomVariableAtom a = (RandomVariableAtom) trainDB.getAtom(simObsRating, j1, j2);
			a.setValue(s);
			trainDB.commit(a);
			avgsim += s;
		}
	}
	System.out.println("  Average joke rating sim (train): " + avgsim / (jokes.size() * jokes.size()));
	//
	//		avgsim = 0.0;
	//		for (GroundTerm u1 : users) {
	//			for (GroundTerm u2 : users) {
	//				double s = userCosSim.getValue(trainDB, u1, u2);
	//				RandomVariableAtom a = (RandomVariableAtom) trainDB.getAtom(simObsTaste, u1, u2);
	//				a.setValue(s);
	//				trainDB.commit(a);
	//				avgsim += s;
	//			}
	//		}
	//		System.out.println("Average user sim (train): " + avgsim / (users.size() * users.size()));
	trainDB.close();

	System.out.println("Populating training database ...");
	toClose = [user,joke,ratingObs,ratingPrior,simJokeText,avgUserRatingObs,avgJokeRatingObs,simObsTaste,simObsRating] as Set;
	trainDB = data.getDatabase(write_tr, toClose, read_tr);
	dbPop = new DatabasePopulator(trainDB);
	dbPop.populate(new QueryAtom(rating, User, Joke), subs);
	Database labelsDB = data.getDatabase(labels_tr, [rating] as Set)

	/* Clear the users, jokes so we can reuse */
	users.clear();
	jokes.clear();

	/* Get the test set users/jokes
	 * and precompute averages
	 */
	System.out.println("Computing averages ...")
	Database testDB = data.getDatabase(read_te)
	results = testDB.executeQuery(Queries.getQueryForAllAtoms(user));
	for (int i = 0; i < results.size(); i++) {
		GroundTerm u = results.get(i)[0];
		users.add(u);
		double avg = userAverager.getValue(testDB, u);
		RandomVariableAtom a = (RandomVariableAtom) testDB.getAtom(avgUserRatingObs, u);
		a.setValue(avg);
		testDB.commit(a);
	}
	results = testDB.executeQuery(Queries.getQueryForAllAtoms(joke));
	for (int i = 0; i < results.size(); i++) {
		GroundTerm j = results.get(i)[0];
		jokes.add(j);
		double avg = jokeAverager.getValue(testDB, j);
		RandomVariableAtom a = (RandomVariableAtom) testDB.getAtom(avgJokeRatingObs, j);
		a.setValue(avg);
		testDB.commit(a);
	}

	/* Compute the prior as average over all observed ratings. */
	avgAllRatingObs = 0.0;
	allRatingObs = Queries.getAllAtoms(testDB, ratingObs);
	for (GroundAtom a : allRatingObs) {
		avgAllRatingObs += a.getValue();
	}
	avgAllRatingObs /= allRatingObs.size();
	System.out.println("  Average rating (test): " + avgAllRatingObs);
	priorAtom = (RandomVariableAtom) testDB.getAtom(ratingPrior, constant);
	priorAtom.setValue(avgAllRatingObs);

	/* Precompute the similarities. */
	System.out.println("Computing testing similarities ...")
	avgsim = 0.0;
	for (GroundTerm j1 : jokes) {
		for (GroundTerm j2 : jokes) {
			double s = jokeCosSim.getValue(testDB, j1, j2);
			RandomVariableAtom a = (RandomVariableAtom) testDB.getAtom(simObsRating, j1, j2);
			a.setValue(s);
			testDB.commit(a);
			avgsim += s;
		}
	}
	System.out.println("  Average joke rating sim (test): " + avgsim / (jokes.size() * jokes.size()));
	//	avgsim = 0.0;
	//	for (GroundTerm u1 : users) {
	//		for (GroundTerm u2 : users) {
	//			double s = userCosSim.getValue(testDB, u1, u2);
	//			RandomVariableAtom a = (RandomVariableAtom) testDB.getAtom(simObsTaste, u1, u2);
	//			a.setValue(s);
	//			testDB.commit(a);
	//			avgsim += s;
	//		}
	//	}
	//	System.out.println("Average user sim (test): " + avgsim / (users.size() * users.size()));
	testDB.close();

	/* Populate testing database. */
	System.out.println("Populating testing database ...");
	toClose = [user,joke,ratingObs,ratingPrior,simJokeText,avgUserRatingObs,avgJokeRatingObs,simObsTaste,simObsRating] as Set;
	testDB = data.getDatabase(write_te, toClose, read_te);
	dbPop = new DatabasePopulator(testDB);
	dbPop.populate(new QueryAtom(rating, User, Joke), subs);

	/*** EXPERIMENT ***/
	System.out.println("Starting experiment ...");
	for (String method : methods) {
		
		/* Weight learning */
		WeightLearner.learn(method, m, trainDB, labelsDB, initWeights, cb, log)

		System.out.println("Learned model " + method + "\n" + m.toString())

		/* Inference on test set */
		Set<GroundAtom> allAtoms = Queries.getAllAtoms(testDB, rating)
		for (RandomVariableAtom atom : Iterables.filter(allAtoms, RandomVariableAtom))
			atom.setValue(0.0)
		MPEInference mpe = new MPEInference(m, testDB, cb)
		FullInferenceResult result = mpe.mpeInference()
		System.out.println("Objective: " + result.getTotalWeightedIncompatibility())
	
		/* Evaluation */
		Database groundTruthDB = data.getDatabase(labels_te, [rating] as Set)
		def comparator = new ContinuousPredictionComparator(testDB)
		comparator.setBaseline(groundTruthDB)
		def metrics = [ContinuousPredictionComparator.Metric.MSE, ContinuousPredictionComparator.Metric.MAE]
		double [] score = new double[metrics.size()]
		for (int i = 0; i < metrics.size(); i++) {
			comparator.setMetric(metrics.get(i))
			score[i] = comparator.compare(rating)
		}
		System.out.println("Fold " + fold + ", MSE " + score[0] + ", MAE " + score[1]);
		scores.add(fold, score);
		groundTruthDB.close()
	}
	trainDB.close()

}