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

import scala.collection.JavaConversions._

import org.clapper.argot._
import ArgotConverters._

import opennlp.maxent._
import opennlp.maxent.io._
import opennlp.model._

object SupervisedTRFeatureExtractor extends App {
  val parser = new ArgotParser("fieldspring run opennlp.fieldspring.tr.app.SupervisedTRMaxentModelTrainer", preUsage = Some("Fieldspring"))

  val wikiCorpusInputFile = parser.option[String](List("c", "corpus"), "corpus", "wiki training corpus input file")
  val wikiTextInputFile = parser.option[String](List("w", "wiki"), "wiki", "wiki text input file")
  val trInputFile = parser.option[String](List("i", "tr-input"), "tr-input", "TR-CoNLL input path")
  val gazInputFile = parser.option[String](List("g", "gaz"), "gaz", "serialized gazetteer input file")
  val stoplistInputFile = parser.option[String](List("s", "stoplist"), "stoplist", "stopwords input file")
  val modelsOutputDir = parser.option[String](List("d", "models-dir"), "models-dir", "models output directory")
  val thresholdParam = parser.option[Double](List("t", "threshold"), "threshold", "maximum distance threshold")

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

  println("Reading toponyms from TR-CoNLL at " + trInputFile.value.get + " ...")
  val toponyms:Set[String] = CorpusInfo.getCorpusInfo(trInputFile.value.get).map(_._1).toSet

  toponyms.foreach(println)

  println("Reading Wikipedia geotags from " + wikiCorpusInputFile.value.get + "...")
  val idsToCoords = new collection.mutable.HashMap[String, Coordinate]
  for (curLine <- localfh.openr(wikiCorpusInputFile.value.get)) {
    val tokens = curLine.split("\t")
    val coordTokens = tokens(2).split(",")
    idsToCoords.put(tokens(0), Coordinate.fromDegrees(coordTokens(0).toDouble, coordTokens(1).toDouble))
  }

  println("Reading serialized gazetteer from " + gazInputFile.value.get + " ...")
  val gis = new GZIPInputStream(new FileInputStream(gazInputFile.value.get))
  val ois = new ObjectInputStream(gis)
  val gnGaz = ois.readObject.asInstanceOf[GeoNamesGazetteer]
  gis.close

  println("Reading Wiki text corpus from " + wikiTextInputFile.value.get + " ...")

  val recognizer = new OpenNLPRecognizer
  val tokenizer = new OpenNLPTokenizer

  val wikiTextCorpus = Corpus.createStreamCorpus

  val reader = localfh.open_buffered_reader(wikiTextInputFile.value.get)
  wikiTextCorpus.addSource(new ToponymAnnotator(new WikiTextSource(reader), recognizer, gnGaz))
  wikiTextCorpus.setFormat(BaseApp.CORPUS_FORMAT.WIKITEXT)

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

  val toponymsToTrainingSets = new collection.mutable.HashMap[String, List[(Array[String], String)]]
  (new Meter("processing", "document")).foreach(wikiTextCorpus) { doc =>
    if(idsToCoords.containsKey(doc.getId)) {
      val docCoord = idsToCoords(doc.getId)
      println(s"${doc.title} (${doc.getId}) has a geotag: $docCoord")
      val docAsArray = TextUtil.getDocAsArray(doc)
      var tokIndex = 0
      for (token <- docAsArray) {
        if (token.isToponym) {
          val toponym = token.asInstanceOf[Toponym]
          if (toponym.getAmbiguity > 0) {
            if(toponyms(token.getForm)) {
              println(token.getForm+" is a toponym we care about.")
              //val bestCellNum = getBestCellNum(toponym, docCoord, dpc)
              val bestCandIndex = getBestCandIndex(toponym, docCoord)
              if(bestCandIndex != -1) {
                val contextFeatures = TextUtil.getContextFeatures(docAsArray, tokIndex, windowSize, stoplist)
                val prevSet = toponymsToTrainingSets.getOrElse(token.getForm, Nil)
                print(toponym+": ")
                contextFeatures.foreach(f => print(f+","))
                println(bestCandIndex)

                toponymsToTrainingSets.put(token.getForm, (contextFeatures, bestCandIndex.toString) :: prevSet)
              }
            } else {
              println(token.getForm+" is a toponym, but we don't care about it.")
            }
          }
        }
        tokIndex += 1
      }
    }
    else {
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
