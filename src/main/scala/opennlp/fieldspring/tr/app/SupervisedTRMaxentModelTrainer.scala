package opennlp.fieldspring
package tr.app

import java.io._
import java.util.zip._

import util.io.localfh
import util.metering._
import tr.util._
import tr.topo._
import tr.topo.gaz._
import tr.text._
import tr.text.prep._
import tr.text.io._

import scala.collection.mutable
import scala.collection.JavaConversions._

import org.clapper.argot._
import ArgotConverters._

import opennlp.maxent._
import opennlp.maxent.io._
import opennlp.model._

/**
 * Extract features for training WISTR models. We need the following files:
 *
 * 1. (-i, --tr-input) Corpus over which WISTR is to be run. We fetch
 *    the toponyms from this file and only extract WISTR features for these
 *    toponyms, for efficiency. This is very important, since we have to
 *    train a separate classifier for every toponym type (in the types vs.
 *    tokens sense).
 * 2. (-c, --corpus) Wikipedia training corpus, a textdb database. This is
 *    used to fetch the coordinates for each Wikipedia article.
 * 3. (-w, --wiki) File containing the actual text of each Wikipedia article.
 * 4. (-g, --gaz) Serialized gazetteer.
 * 5. (-s, --stoplist) File containing stopwords.
 *
 * We proceed as follows:
 *
 * 1. We read in the '--tr-input' file and make a list of all distinct
 *    toponyms, i.e. all toponym types for which we need to extract features.
 * 2. We read in the Wikipedia training corpus and make a list of all
 *    Wikipedia articles and their coordinates.
 * 3. We read in the text of the Wikipedia articles and pass the text
 *    through a toponym annotator, which uses the OpenNLP named entity
 *    recognizer to find toponyms and looks the toponyms up in the gazetteer.
 * 4. For each article, we first make sure it has a coordinate, then
 *    go through all its toponyms, and for the toponyms we "care about"
 *    (for which we need to extract features), if the toponym has a candidate
 *    that is within '--threshold' distance (default 10km) of the article's
 *    coordinate, we assume the toponym resolves to that candidate and
 *    create a data instance for a candidate classifier for the toponym,
 *    whose label is the candidate and whose features are the words surrounding
 *    the toponym instance. The goal here is to train a classifier over the
 *    candidates of a toponym type, so that we can use the text surrounding
 *    an unresolved toponym in the test set to resolve that toponym.
 * 5. For each toponym type, we gather all the data instances we have created
 *    for that toponym type and use OpenNLP to train a classifier to resolve
 *    toponyms of that type.
 */
object SupervisedTRFeatureExtractor extends App {
  val parser = new ArgotParser("fieldspring run opennlp.fieldspring.tr.app.SupervisedTRMaxentModelTrainer", preUsage = Some("Fieldspring"))

  val wikiCorpusInputFile = parser.option[String](List("c", "corpus"), "corpus", "wiki training corpus input file")
  val wikiTextInputFile = parser.option[String](List("w", "wiki"), "wiki", "wiki text input file")
  val trInputFile = parser.option[String](List("i", "tr-input"), "tr-input", "TR-CoNLL input path")
  val gazInputFile = parser.option[String](List("g", "gaz"), "gaz", "serialized gazetteer input file")
  val stoplistInputFile = parser.option[String](List("s", "stoplist"), "stoplist", "stopwords input file")
  val modelsOutputDir = parser.option[String](List("d", "models-dir"), "models-dir", "models output directory")
  val thresholdParam = parser.option[Double](List("t", "threshold"), "threshold", "maximum distance threshold")
  val corpusFormat = parser.option[String](List("f", "corpus-format"), "corpus-format", "format of --wiki: wikitext (default), trconll, toponymwiki")
  val coordFile = parser.flag[Boolean](List("coord-file"), "--corpus specifies wiki coord file")
  val verboseParam = parser.flag[Boolean](List("verbose"), "output verbose info")

  try {
    parser.parse(args)
  }
  catch {
    case e: ArgotUsageException => println(e.message); sys.exit(0)
  }

  val windowSize = 20
  val dpc = 1.0

  val distanceTable = new DistanceTable

  val threshold = if(thresholdParam.value != None) thresholdParam.value.get else 10.0
  val verbose = verboseParam.value.getOrElse(false)

  println("Reading toponyms from TR-CoNLL at " + trInputFile.value.get + " ...")
  val toponyms:Set[String] = CorpusInfo.getCorpusInfo(trInputFile.value.get).map(_._1).toSet

  toponyms.foreach(println)

  println("Reading Wikipedia geotags from " + wikiCorpusInputFile.value.get + "...")
  val idsToCoords = mutable.Map[String, Coordinate]()

  def putCoord(id: String, coord: String) {
    val coordTokens = coord.split(",")
    idsToCoords.put(id, Coordinate.fromDegrees(coordTokens(0).toDouble, coordTokens(1).toDouble))
  }
  val isCoordFile = coordFile.value.getOrElse(false)
  if (isCoordFile) {
    val title_re = "^Article title: (.*)$".r
    val id_re = "^Article ID: (.*)$".r
    val coord_re = "^Article coordinates: (.*)$".r
    var title = ""
    var id = ""
    for (line <- localfh.openr(wikiCorpusInputFile.value.get)) {
      line match {
        case title_re(t) => { title = t }
        case id_re(i) => { id = i }
        case coord_re(c) => { putCoord(id, c) }
        case _ => { }
      }
    }
  } else {
    for (curLine <- localfh.openr(wikiCorpusInputFile.value.get)) {
      val tokens = curLine.split("\t")
      putCoord(tokens(0), tokens(2))
    }
  }

  println("Reading serialized gazetteer from " + gazInputFile.value.get + " ...")
  val gis = new GZIPInputStream(new FileInputStream(gazInputFile.value.get))
  val ois = new ObjectInputStream(gis)
  val gnGaz = ois.readObject.asInstanceOf[GeoNamesGazetteer]
  gis.close

  println("Reading Wiki text corpus from " + wikiTextInputFile.value.get + " ...")

  val recognizer = new OpenNLPRecognizer
  val divider = new OpenNLPSentenceDivider
  val tokenizer = new OpenNLPTokenizer

  val inputfn = wikiTextInputFile.value.get
  val corpusFmt = corpusFormat.value.getOrElse("wikitext")
  val isGold = corpusFmt != "wikitext"

  val wikiTextCorpus =
    //if (isGold && inputfn.endsWith(".ser.gz"))
    //  TopoUtil.readStoredCorpusFromSerialized(inputfn)
    //else
    {
      val reader = localfh.open_buffered_reader(inputfn)
      val corpus = Corpus.createStreamCorpus
      if (corpusFmt == "toponymwiki") {
        corpus.addSource(new ToponymWikiSource(reader, divider, tokenizer, gnGaz, verbose = verbose))
        corpus.setFormat(BaseApp.CORPUS_FORMAT.TOPOWIKITEXT)
      } else if (corpusFmt == "trconll") {
        corpus.addSource(new TrXMLSource(reader, tokenizer))
        corpus.setFormat(BaseApp.CORPUS_FORMAT.TRCONLL)
      } else {
        corpus.addSource(new ToponymAnnotator(new WikiTextSource(reader), recognizer, gnGaz))
        corpus.setFormat(BaseApp.CORPUS_FORMAT.WIKITEXT)
      }
      corpus
    }

  val stoplist:Set[String] =
    if(stoplistInputFile.value != None) {
      println("Reading stopwords file from " + stoplistInputFile.value.get + " ...")
      localfh.openr(stoplistInputFile.value.get).toSet
    }
    else {
      println("No stopwords file specified. Using an empty stopword list.")
      Set()
    }

  println("Building training sets for each toponym type...")

  // Map from toponyms to training data. The training data is a list of
  // data instances, each of which is a tuple consisting of an array of
  // features and a label.
  val toponymsToTrainingSets = new collection.mutable.HashMap[String, List[(Array[String], String)]]
  (new Meter("processing", "document")).foreach(wikiTextCorpus) { doc =>
    if(isGold || idsToCoords.containsKey(doc.getId)) {
      val docCoord = idsToCoords.getOrElse(doc.getId, null)
      if (verbose)
        println(s"Processing ${doc.title} (${doc.getId}) with geotag $docCoord")
      val docAsArray = TextUtil.getDocAsArray(doc)
      var tokIndex = 0
      for (token <- docAsArray) {
        if (token.isToponym) {
          val toponym = token.asInstanceOf[Toponym]
          if (toponym.getAmbiguity > 0) {
            if(toponyms(token.getForm)) {
              if (verbose)
                println(token.getForm+" is a toponym we care about.")
              //val bestCellNum = getBestCellNum(toponym, docCoord, dpc)
              val bestCandIndex =
                if (isGold)
                  toponym.getGoldIdx
                else
                  getBestCandIndex(toponym, docCoord)
              if(bestCandIndex != -1) {
                val contextFeatures = TextUtil.getContextFeatures(docAsArray, tokIndex, windowSize, stoplist)
                val prevSet = toponymsToTrainingSets.getOrElse(token.getForm, Nil)
                if (verbose) {
                  print(toponym+": ")
                  contextFeatures.foreach(f => print(f+","))
                  println(bestCandIndex)
                }

                toponymsToTrainingSets.put(token.getForm, (contextFeatures, bestCandIndex.toString) :: prevSet)
              }
            } else {
              if (verbose)
                println(token.getForm+" is a toponym, but we don't care about it.")
            }
          }
        }
        tokIndex += 1
      }
    }
    else {
      if (verbose)
        println(s"${doc.title} (${doc.getId}) does not have a geotag.")
      for(sent <- doc) { for(token <- sent) {} }
    }
  }

  val dir =
    if(modelsOutputDir.value.get != None) {
      println("Training Maxent models for each toponym type, outputting to directory " + modelsOutputDir.value.get + " ...")
      val dirFile:File = new File(modelsOutputDir.value.get)
      if(!dirFile.exists)
        dirFile.mkdir
      if(modelsOutputDir.value.get.endsWith("/"))
        modelsOutputDir.value.get
      else
        modelsOutputDir.value.get+"/"
    }
    else {
      println("Training Maxent models for each toponym type, outputting to current working directory ...")
      ""
    }
  for((toponym, trainingSet) <- toponymsToTrainingSets) {
    val outFile = new File(dir + toponym.replaceAll(" ", "_")+".txt")
    val out = new BufferedWriter(new FileWriter(outFile))
    for((context, label) <- trainingSet) {
      for(feature <- context) out.write(feature+",")
      out.write(label+"\n")
    }
    out.close
  }

  println("All done.")

  def getBestCandIndex(toponym:Toponym, docCoord:Coordinate): Int = {
    var index = 0
    var minDist = Double.PositiveInfinity
    var bestIndex = -1
    val docRegion = new PointRegion(docCoord)
    for(loc <- toponym.getCandidates) {
      val dist = loc.getRegion.distanceInKm(docRegion)
      if(dist < /*loc.getThreshold*/ threshold && dist < minDist) {
        minDist = dist
        bestIndex = index
      }
      index += 1
    }
    bestIndex
  }

  /*def getBestCellNum(toponym:Toponym, docCoord:Coordinate, dpc:Double): Int = {
    for(loc <- toponym.getCandidates) {
      if(loc.getRegion.distanceInKm(new PointRegion(docCoord)) < loc.getThreshold) {
        return TopoUtil.getCellNumber(loc.getRegion.getCenter, dpc)
      }
    }
    -1
  }*/

}

object SupervisedTRMaxentModelTrainer extends App {
  val iterations = 10
  val cutoff = 2

  val dir = new File(args(0))
  val files = dir.listFiles.filter(_.getName.endsWith(".txt"))
  (new Meter("training", "file")).foreach(files) { file =>
    try {
      val reader = localfh.open_buffered_reader(file.toString)
      val dataStream = new PlainTextByLineDataStream(reader)
      val eventStream = new BasicEventStream(dataStream, ",")

      //GIS.PRINT_MESSAGES = false
      val model = GIS.trainModel(eventStream, iterations, cutoff)
      val modelWriter = new BinaryGISModelWriter(model, new File(file.getAbsolutePath.replaceAll(".txt", ".mxm")))
      modelWriter.persist()
      modelWriter.close()
    } catch {
      case e: Exception => e.printStackTrace
    }
  }
}

object MaxentEventStreamFactory {
  def apply(iterator:Iterator[(Array[String], String)]): EventStream = {
    new BasicEventStream(new DataStream {
      def nextToken: AnyRef = {
        val next = iterator.next
        val featuresAndLabel = (next._1.toList ::: (next._2 :: Nil)).mkString(",")
        println(featuresAndLabel)
        featuresAndLabel
      }
      def hasNext: Boolean = iterator.hasNext
    }, ",")
  }
}
