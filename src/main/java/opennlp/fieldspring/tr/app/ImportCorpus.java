/* This class takes a serialized gazetteer and a corpus, and outputs a preprocessed, serialized version of that corpus, ready to be read in quickly by RunResolver.
 */

package opennlp.fieldspring.tr.app;

import opennlp.fieldspring.tr.text.*;
import opennlp.fieldspring.tr.text.io.*;
import opennlp.fieldspring.tr.util.IOUtil;
import opennlp.fieldspring.tr.text.prep.*;
import opennlp.fieldspring.tr.topo.gaz.*;
import java.io.*;
import java.util.zip.*;

public class ImportCorpus extends BaseApp {

    //private static int sentsPerDocument;
    
    public static void main(String[] args) throws Exception {

        ImportCorpus currentRun = new ImportCorpus();

        currentRun.initializeOptionsFromCommandLine(args);
        //sentsPerDocument = currentRun.getSentsPerDocument();

        if(currentRun.getSerializedCorpusOutputPath() == null && currentRun.getOutputPath() == null) {
            System.out.println("Please specify a serialized corpus output file with the -sco flag and/or an XML output file with the -o flag.");
            System.exit(0);
        }

        StoredCorpus corpus = currentRun.doImport(currentRun.getInputPath(), currentRun.getSerializedGazetteerPath(), currentRun.getCorpusFormat(), currentRun.getUseGoldToponyms(), currentRun.getSentsPerDocument());
        
        if(currentRun.getSerializedCorpusOutputPath() != null)
            currentRun.serialize(corpus, currentRun.getSerializedCorpusOutputPath());
        if(currentRun.getOutputPath() != null)
            currentRun.writeToXML(corpus, currentRun.getOutputPath());
    }

    public StoredCorpus doImport(String corpusInputPath, String serGazInputPath,
                                        Enum<BaseApp.CORPUS_FORMAT> corpusFormat) throws Exception {
        return doImport(corpusInputPath, serGazInputPath, corpusFormat, false, -1);
    }

    public StoredCorpus doImport(String corpusInputPath, String serGazInputPath,
                                        Enum<BaseApp.CORPUS_FORMAT> corpusFormat,
                                 boolean useGoldToponyms, int sentsPerDocument) throws Exception {

        checkExists(corpusInputPath);
        if(!useGoldToponyms || doKMeans)
            checkExists(serGazInputPath);

        Tokenizer tokenizer = new OpenNLPTokenizer();
        SentenceDivider divider = new OpenNLPSentenceDivider();
        OpenNLPRecognizer recognizer = new OpenNLPRecognizer();

        GeoNamesGazetteer gnGaz = null;
        System.out.println("Reading serialized GeoNames gazetteer from " + serGazInputPath + " ...");
        ObjectInputStream ois = null;
        if(serGazInputPath.toLowerCase().endsWith(".gz")) {
            GZIPInputStream gis = new GZIPInputStream(new FileInputStream(serGazInputPath));
            ois = new ObjectInputStream(gis);
        }
        else {
            FileInputStream fis = new FileInputStream(serGazInputPath);
            ois = new ObjectInputStream(fis);
        }
        gnGaz = (GeoNamesGazetteer) ois.readObject();
        if(isHighRecallNER())
        	recognizer = new HighRecallToponymRecognizer(gnGaz.getUniqueLocationNameSet());
        System.out.println("Done.");

        System.out.print("Reading raw corpus from " + corpusInputPath + " ...");
        StoredCorpus corpus = Corpus.createStoredCorpus();
        File corpusInputFile = new File(corpusInputPath);
        if(corpusFormat == CORPUS_FORMAT.TRCONLL) {
            if(useGoldToponyms) {
                if(corpusInputFile.isDirectory())
                    corpus.addSource(new CandidateRepopulator(new TrXMLDirSource(new File(corpusInputPath), tokenizer, sentsPerDocument), gnGaz));
                else
                    corpus.addSource(new CandidateRepopulator(new TrXMLSource(IOUtil.createBufferedReader(corpusInputPath), tokenizer, sentsPerDocument), gnGaz));
            }
            else {
                if(corpusInputFile.isDirectory())
                    corpus.addSource(new ToponymAnnotator(
                                                          new ToponymRemover(new TrXMLDirSource(new File(corpusInputPath), tokenizer, sentsPerDocument)),
                           recognizer, gnGaz, null));
                else
                    corpus.addSource(new ToponymAnnotator(
                                                          new ToponymRemover(new TrXMLSource(IOUtil.createBufferedReader(corpusInputPath), tokenizer, sentsPerDocument)),
                           recognizer, gnGaz, null));
            }
        }
        else if(corpusFormat == CORPUS_FORMAT.TOPOWIKITEXT) {
            corpus.addSource(new ToponymWikiSource(
                IOUtil.createBufferedReader(corpusInputPath),
                divider, tokenizer, gnGaz, false));
        }
        else if(corpusFormat == CORPUS_FORMAT.GEOTEXT) {
            corpus.addSource(new ToponymAnnotator(new GeoTextSource(
                IOUtil.createBufferedReader(corpusInputPath), tokenizer),
                recognizer, gnGaz, null));
        }
	else if (!corpusInputFile.isDirectory()) {
            corpus.addSource(new ToponymAnnotator(new PlainTextSource(
                             IOUtil.createBufferedReader(corpusInputPath), divider, tokenizer, corpusInputPath),
                recognizer, gnGaz, null));
	}
        else {
            corpus.addSource(new ToponymAnnotator(new PlainTextDirSource(
                new File(corpusInputPath), new OpenNLPSentenceDivider(), tokenizer),
                recognizer, gnGaz, null));
        }
        corpus.setFormat(corpusFormat);
        //if(corpusFormat != CORPUS_FORMAT.GEOTEXT)
        corpus.load();
        System.out.println("done.");

        System.out.println("\nNumber of documents: " + corpus.getDocumentCount());
        System.out.println("Number of word tokens: " + corpus.getTokenCount());
        System.out.println("Number of word types: " + corpus.getTokenTypeCount());
        System.out.println("Number of toponym tokens: " + corpus.getToponymTokenCount());
        System.out.println("Number of toponym types: " + corpus.getToponymTypeCount());
        System.out.println("Average ambiguity (locations per toponym): " + corpus.getAvgToponymAmbiguity());
        System.out.println("Maximum ambiguity (locations per toponym): " + corpus.getMaxToponymAmbiguity());

        return corpus;
    }

    public void serialize(Corpus<? extends Token> corpus, String serializedCorpusPath) throws Exception {

        System.out.print("\nSerializing corpus to " + serializedCorpusPath + " ...");
        
        ObjectOutputStream oos = null;
        if(serializedCorpusPath.toLowerCase().endsWith(".gz")) {
            GZIPOutputStream gos = new GZIPOutputStream(new FileOutputStream(serializedCorpusPath));
            oos = new ObjectOutputStream(gos);
        }
        else {
            FileOutputStream fos = new FileOutputStream(serializedCorpusPath);
            oos = new ObjectOutputStream(fos);
        }
        oos.writeObject(corpus);
        oos.close();
        
        System.out.println("done.");
    }

    public void writeToXML(Corpus<? extends Token> corpus, String xmlOutputPath) throws Exception {
        System.out.print("\nWriting corpus in XML format to " + xmlOutputPath + " ...");
        CorpusXMLWriter w = new CorpusXMLWriter(corpus);
        w.write(new File(xmlOutputPath));
        System.out.println("done.");
    }

}
