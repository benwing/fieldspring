/*
 * Evaluator that uses signatures around each gold and predicted toponym to be used in the computation of P/R/F.
 */

package opennlp.fieldspring.tr.eval;

import opennlp.fieldspring.tr.text.*;
import opennlp.fieldspring.tr.topo.*;
import java.util.*;
import java.io.*;

public class SignatureEvaluator<A extends Token> extends Evaluator<A> {

    private static final int CONTEXT_WINDOW_SIZE = 20;

    private static final double FP_PENALTY = 20037.5;
    private static final double FN_PENALTY = 20037.5;

    private boolean doOracleEval;

    private Map<String, List<Location> > predCandidates = new HashMap<String, List<Location> >();

    // Map describing the correct location for each toponym in the predicted
    // corpus, as determined by the gold corpus.
    public Map<Toponym, Location> correctLocations = new HashMap<Toponym, Location>();

    public SignatureEvaluator(Corpus<A> goldCorpus, boolean doOracleEval) {
        super(goldCorpus);
        this.doOracleEval = doOracleEval;
    }

    public SignatureEvaluator(Corpus<A> goldCorpus) {
        this(goldCorpus, false);
    }

    public Report evaluate() {
        return null;
    }

    /**
     * Return a map from toponym signatures to locations. For each toponym, we
     * fetch a "signature", i.e. a string consisting of the words surrounding
     * the toponym in some context window (CONTEXT_WINDOW_SIZE, in characters,
     * ignoring non-alphanumeric characters). The map lists either the selected
     * or gold location associated with the toponym, depending on the value of
     * `getGoldLocations`. If we are fetching non-gold locations, we also
     * populate the class variable `predCandidates` with a map from signatures
     * to the list of all candidates for a toponym.  We also store into
     * `signatureToToponym` (if non-null) a mapping back from signatures to the
     * original toponym.
     */
    private Map<String, Location> populateSigsAndLocations(Corpus<A> corpus, boolean getGoldLocations, Map<String, Toponym> signatureToToponym) {
        Map<String, Location> locs = new HashMap<String, Location>();

        for(Document<A> doc : corpus) {
            //System.out.println("Document id: " + doc.getId());
            for(Sentence<A> sent : doc) {
                StringBuffer sb = new StringBuffer();
                List<Integer> toponymStarts = new ArrayList<Integer>();
                List<Toponym> curToponyms = new ArrayList<Toponym>();
                List<Location> curLocations = new ArrayList<Location>();
                List<List<Location> > curCandidates = new ArrayList<List<Location> >();
                for(A token : sent) {
                    //System.out.println(token.getForm());
                    if(token.isToponym()) {
                        Toponym toponym = (Toponym) token;
                        if((getGoldLocations && toponym.hasGold()) ||
                           (!getGoldLocations && (toponym.hasSelected() || toponym.getAmbiguity() == 0))) {
                            //System.out.println("Saw " + toponym.getForm()+": "+toponym.getGoldIdx()+"/"+toponym.getCandidates().size());
                            toponymStarts.add(sb.length());
                            curToponyms.add(toponym);
                            if(getGoldLocations) {
				/*if(toponym.getGoldIdx() == 801) {
				    System.out.println(toponym.getForm()+": "+toponym.getGoldIdx()+"/"+toponym.getCandidates().size());
                                    }*/
                                curLocations.add(toponym.getCandidates().get(toponym.getGoldIdx()<toponym.getCandidates().size()?toponym.getGoldIdx():toponym.getCandidates().size()-1));
			    }
                            else {
                                if(toponym.getAmbiguity() > 0)
                                    curLocations.add(toponym.getCandidates().get(toponym.getSelectedIdx()));
                                else
                                    curLocations.add(null);
                                curCandidates.add(toponym.getCandidates());
                            }
                        }
                    }
                    sb.append(token.getForm().replaceAll("[^a-z0-9]", ""));
                }
                for(int i = 0; i < toponymStarts.size(); i++) {
                    int toponymStart = toponymStarts.get(i);
                    Location curLoc = curLocations.get(i);
                    Toponym curTop = curToponyms.get(i);
                    String context = getSignature(sb, toponymStart, CONTEXT_WINDOW_SIZE) + doc.getId();
                    locs.put(context, curLoc);
                    if(!getGoldLocations)
                        predCandidates.put(context, curCandidates.get(i));
                    if (signatureToToponym != null)
                        signatureToToponym.put(context, curTop);
                }
            }
        }

        return locs;
    }

    private DistanceReport dreport = null;
    public DistanceReport getDistanceReport() { return dreport; }

    /**
     * Evaluate the given corpus by comparing the location of each toponym
     * in the gold corpus (given as a constructor argument) with the selected
     * location of the correponding toponym in `pred`. Toponyms are matched
     * up by comparing "signatures" (the concatenation of all words in a
     * context window surrounding the toponym), and the predicted location
     * is considered correct if it's closer to the gold location than any
     * other possible candidate. For a given gold toponym, a true positive
     * occurs if a predicted toponym is found and has a matching location;
     * if a predicted toponym is found but has a non-matching location, both
     * a false positive and false negative occurs, and a false negative also
     * occurs when no predicted toponym can be found.
     */
    @Override
    public Report evaluate(Corpus<A> pred, boolean useSelected) {
        
        Report report = new Report();
        dreport = new DistanceReport();

        Map<String, Toponym> signatureToPredTop = new HashMap<String, Toponym>();
        Map<String, Location> goldLocs =
            populateSigsAndLocations(corpus, true, null);
        Map<String, Location> predLocs =
            populateSigsAndLocations(pred, false, signatureToPredTop);

        Map<String, List<Double> > errors = new HashMap<String, List<Double> >();

        for(String context : goldLocs.keySet()) {
            if(predLocs.containsKey(context)) {
                Location goldLoc = goldLocs.get(context);
                Location predLoc = predLocs.get(context);

                if(predLoc != null && !doOracleEval) {
                    double dist = goldLoc.distanceInKm(predLoc);
                    dreport.addDistance(dist);
                    String key = goldLoc.getName().toLowerCase();
                    if(!errors.containsKey(key))
                        errors.put(key, new ArrayList<Double>());
                    errors.get(key).add(dist);
                }

                // Record the "correct" location (closest one to the gold
                // corpus location) for the predicted toponym,
                // using the map from signatures to predicted toponyms.
                if(predCandidates.get(context).size() > 0) {
                    Location closestMatch = getClosestMatch(goldLoc, predCandidates.get(context));
                    correctLocations.put(signatureToPredTop.get(context), closestMatch);
                }

                if(doOracleEval) {
                    if(predCandidates.get(context).size() > 0) {
                        Location closestMatch = getClosestMatch(goldLoc, predCandidates.get(context));
                        double dist = goldLoc.distanceInKm(closestMatch);
                        dreport.addDistance(dist);
                        report.incrementTP();
                        String key = goldLoc.getName().toLowerCase();
                        if(!errors.containsKey(key))
                            errors.put(key, new ArrayList<Double>());
                        errors.get(key).add(dist);
                    }
                }
                else {
                    if(isClosestMatch(goldLoc, predLoc, predCandidates.get(context))) {//goldLocs.get(context) == predLocs.get(context)) {}
                        //System.out.println("TP: " + context + "|" + goldLocs.get(context));
                        report.incrementTP();
                    }
                    else {
                        //System.out.println("FP and FN: " + context + "|" + goldLocs.get(context) + " vs. " + predLocs.get(context));
                        //report.incrementFP();
                        //report.incrementFN();
                        report.incrementFPandFN();
                    }
                }
            }
            else {
                //System.out.println("FN: " + context + "| not found in pred");
                report.incrementFN();
                //dreport.addDistance(FN_PENALTY);
                
            }
        }
        for(String context : predLocs.keySet()) {
            if(!goldLocs.containsKey(context)) {
                //System.out.println("FP: " + context + "| not found in gold");
                report.incrementFP();
                //dreport.addDistance(FP_PENALTY);
            }
        }

        try {
            BufferedWriter errOut = new BufferedWriter(new FileWriter("errors.txt"));

            for(String toponym : errors.keySet()) {
                List<Double> errorList = errors.get(toponym);
                double sum = 0.0;
                for(double error : errorList) {
                    sum += error;
                }
                errOut.write(toponym+" & "+errorList.size()+" & "+(sum/errorList.size())+" & "+sum+"\\\\\n");
            }
            
            errOut.close();
            
        } catch(Exception e) {
            e.printStackTrace();
            System.exit(1);
        }

        return report;
    }

    private boolean isClosestMatch(Location goldLoc, Location predLoc, List<Location> curPredCandidates) {
        if(predLoc == null)
            return false;

        double distanceToBeat = predLoc.distance(goldLoc);

        for(Location otherLoc : curPredCandidates) {
            if(otherLoc.distance(goldLoc) < distanceToBeat)
                return false;
        }
        return true;
    }

    private Location getClosestMatch(Location goldLoc, List<Location> curPredCandidates) {
        double minDist = Double.POSITIVE_INFINITY;
        Location toReturn = null;

        for(Location otherLoc : curPredCandidates) {
            double dist = otherLoc.distance(goldLoc);
            if(dist < minDist) {
                minDist = dist;
                toReturn = otherLoc;
            }
        }

        return toReturn;
    }

    private String getSignature(StringBuffer wholeContext, int centerIndex, int windowSize) {
        int beginIndex = Math.max(0, centerIndex - windowSize);
        int endIndex = Math.min(wholeContext.length(), centerIndex + windowSize);

        return wholeContext.substring(beginIndex, endIndex);
    }
}
