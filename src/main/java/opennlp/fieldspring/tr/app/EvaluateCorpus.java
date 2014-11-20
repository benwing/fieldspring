/* Evaluates a given corpus with system disambiguated toponyms againt a given gold corpus.
 */

package opennlp.fieldspring.tr.app;

import opennlp.fieldspring.tr.resolver.*;
import opennlp.fieldspring.tr.text.*;
import opennlp.fieldspring.tr.text.io.*;
import opennlp.fieldspring.tr.text.prep.*;
import opennlp.fieldspring.tr.topo.gaz.*;
import opennlp.fieldspring.tr.eval.*;
import opennlp.fieldspring.tr.util.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

public class EvaluateCorpus extends BaseApp {

    public static void main(String[] args) throws Exception {

        EvaluateCorpus currentRun = new EvaluateCorpus();
        currentRun.initializeOptionsFromCommandLine(args);

        if(currentRun.getCorpusFormat() == CORPUS_FORMAT.TRCONLL) {
            if(currentRun.getInputPath() == null || (currentRun.getSerializedCorpusInputPath() == null && currentRun.getXMLInputPath() == null)) {
                System.out.println("Please specify both a system annotated corpus file via the -sci or -ix flag and a gold plaintext corpus file via the -i flag.");
                System.exit(0);
            }
        }
        else {
            if(currentRun.getSerializedCorpusInputPath() == null && currentRun.getXMLInputPath() == null) {
                System.out.println("Please specify a system annotated corpus file via the -sci or -ix flag.");
                System.exit(0);
            }
        }

        StoredCorpus systemCorpus;
        if(currentRun.getSerializedCorpusInputPath() != null) {
            System.out.print("Reading serialized system corpus from " + currentRun.getSerializedCorpusInputPath() + " ...");
            systemCorpus = TopoUtil.readStoredCorpusFromSerialized(currentRun.getSerializedCorpusInputPath());
            System.out.println("done.");
        }
        else {// if(getXMLInputPath() != null) {
            Tokenizer tokenizer = new OpenNLPTokenizer();
            systemCorpus = Corpus.createStoredCorpus();
            systemCorpus.addSource(new CorpusXMLSource(new BufferedReader(new FileReader(currentRun.getXMLInputPath())),
                                                       tokenizer));
            systemCorpus.setFormat(currentRun.getCorpusFormat()==null?CORPUS_FORMAT.PLAIN:currentRun.getCorpusFormat());
            systemCorpus.load();
        }

        StoredCorpus goldCorpus = null;

        if(currentRun.getInputPath() != null && currentRun.getCorpusFormat() == CORPUS_FORMAT.TRCONLL) {
            Tokenizer tokenizer = new OpenNLPTokenizer();
            System.out.print("Reading plaintext gold corpus from " + currentRun.getInputPath() + " ...");
            goldCorpus = Corpus.createStoredCorpus();
            goldCorpus.addSource(new TrXMLDirSource(new File(currentRun.getInputPath()), tokenizer));
            goldCorpus.load();
            System.out.println("done.");
        }

        currentRun.doEval(systemCorpus, goldCorpus, "",
            currentRun.getCorpusFormat(), currentRun.getUseGoldToponyms(),
            currentRun.getDoOracleEval());
    }

    public void doEval(StoredCorpus systemCorpus, StoredCorpus goldCorpus,
            String prefix, Enum<BaseApp.CORPUS_FORMAT> corpusFormat,
            boolean useGoldToponyms) throws Exception {
        this.doEval(systemCorpus, goldCorpus, prefix, corpusFormat, useGoldToponyms, false);
    }

    public void doEval(StoredCorpus systemCorpus, StoredCorpus goldCorpus,
            String prefix, Enum<BaseApp.CORPUS_FORMAT> corpusFormat,
            boolean useGoldToponyms, boolean doOracleEval) throws Exception {
        System.out.print("\nEvaluating...");
        if(corpusFormat == CORPUS_FORMAT.GEOTEXT) {
            DocDistanceEvaluator evaluator = new DocDistanceEvaluator(systemCorpus);
            DistanceReport dreport = evaluator.evaluate();

            System.out.println("\n" + prefix + "Minimum error distance (km): " + dreport.getMinDistance());
            System.out.println(prefix + "Maximum error distance (km): " + dreport.getMaxDistance());
            System.out.println("\n" + prefix + "Mean error distance (km): " + dreport.getMeanDistance());
            System.out.println(prefix + "Median error distance (km): " + dreport.getMedianDistance());
            System.out.println(prefix + "Fraction of distances within 161 km: " + dreport.getFractionDistancesWithinThreshold(161.0));
            System.out.println("\n" + prefix + "Total documents evaluated: " + dreport.getNumDistances());
        }

        else {
            SignatureEvaluator<StoredToken> evaluator = new SignatureEvaluator<StoredToken>(goldCorpus, doOracleEval);
            Report report = evaluator.evaluate(systemCorpus, false);
            DistanceReport dreport = evaluator.getDistanceReport();

            System.out.println("\n" + prefix + "P: " + report.getPrecision());
            System.out.println(prefix + "R: " + report.getRecall());
            System.out.println(prefix + "F: " + report.getFScore());
            //System.out.println("A: " + report.getAccuracy());

            System.out.println("\n" + prefix + "Minimum error distance (km): " + dreport.getMinDistance());
            System.out.println(prefix + "Maximum error distance (km): " + dreport.getMaxDistance());
            System.out.println("\n" + prefix + "Mean error distance (km): " + dreport.getMeanDistance());
            System.out.println(prefix + "Median error distance (km): " + dreport.getMedianDistance());
            System.out.println(prefix + "Fraction of distances within 161 km: " + dreport.getFractionDistancesWithinThreshold(161.0));
            System.out.println("\n" + prefix + "Total toponyms evaluated: " + dreport.getNumDistances());
        }
    }

}
