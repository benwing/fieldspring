package opennlp.fieldspring
package tr.resolver

import java.io._
import java.util.ArrayList

import util.print.errprint
import tr.text._
import tr.topo._
import tr.util._

import opennlp.maxent._
import opennlp.maxent.io._
import opennlp.model._

import scala.collection.JavaConversions._

class ProbabilisticResolver(
  val logFilePath:String,
  val modelDirPath:String,
  val weightDataPath:String,
  val popComponentCoefficient:Double,
  val dgProbOnly:Boolean,
  val meProbOnly:Boolean
) extends Resolver {

  val KNN = -1
  val WINDOW_SIZE = 20

  val C = 1.0E-4 // used in f(t)/(f(t)+C)

  def disambiguate(corpus:StoredCorpus): StoredCorpus = {

  val toponymLexicon:Lexicon[String] = TopoUtil.buildLexicon(corpus)
  val weightsForWMD:ArrayList[ArrayList[Double]] = new ArrayList[ArrayList[Double]](toponymLexicon.size)
  for(i <- 0 until toponymLexicon.size) weightsForWMD.add(null)

  val logElements = LogUtil.parseLogFile(logFilePath)
  val docIdToCellDist:Map[String, Map[RectRegion, Double]] =
    (for(pe <- logElements) yield {
      (pe.docName, pe.getProbDistOverPredCells(KNN).toMap)
    }).toMap

  val predDocLocations = (for(pe <- logElements) yield {
    (pe.docName, pe.predCoord)
  }).toMap

  val modelDir = new File(modelDirPath)

  val toponymsToModels:Map[String, AbstractModel] =
  (for(file <- modelDir.listFiles.filter(_.getName.endsWith(".mxm"))) yield {
    val dataInputStream = new DataInputStream(new FileInputStream(file));
    val reader = new BinaryGISModelReader(dataInputStream)
    val model = reader.getModel

    //println(file.getName.dropRight(4).replaceAll("_", " "))
    (file.getName.dropRight(4).replaceAll("_", " "), model)
  }).toMap

  var total = 0
  var toponymsToCounts = //new scala.collection.mutable.HashMap[String, Int]
  (for(file <- modelDir.listFiles.filter(_.getName.endsWith(".txt"))) yield {
    val count = scala.io.Source.fromFile(file).getLines.toList.size
    total += count

    //println(file.getName.dropRight(4).replaceAll("_", " ") + " " + count)

    (file.getName.dropRight(4).replaceAll("_", " "), count)
  }).toMap

  //println(total)
  /*var total = 0
  for(doc <- corpus) {
    for(sent <- doc) {
      for(token <- sent) {
        if(token.isToponym) {
          val prevCount = toponymsToCounts.getOrElse(token.getForm, 0)
          toponymsToCounts.put(token.getForm, prevCount + 1)
        }
        total += 1
      }
    }
  }*/

  val toponymsToFrequencies = toponymsToCounts.map(p => (p._1, p._2.toDouble / total)).toMap
  //toponymsToCounts.clear // trying to free up some memory
  toponymsToCounts = null
  //toponymsToFrequencies.foreach(p => println(p._1+": "+p._2))

  for(doc <- corpus) {
    val docAsArray = TextUtil.getDocAsArray(doc)
    var tokIndex = 0
    for(token <- docAsArray) {
      if(token.isToponym && token.asInstanceOf[Toponym].getAmbiguity > 0) {
        val toponym = token.asInstanceOf[Toponym]

        //print("\n" + toponym.getForm + " ")

        // P(l|t,d_c(t))
        val candDistGivenLocalContext =
        if(toponymsToModels.containsKey(toponym.getForm)) {
          val contextFeatures = TextUtil.getContextFeatures(docAsArray, tokIndex, WINDOW_SIZE, Set[String]())

          MaxentResolver.getIndexToWeightMap(toponymsToModels(toponym.getForm), contextFeatures)

          //println("getting a cell dist for "+toponym.getForm)

          /*val d = */ //MaxentResolver.getIndexToWeightMap(toponymsToModels(toponym.getForm), contextFeatures)
          //println(d.size)
          //d.foreach(println)
          //d

          
        }
        else
          null

        // P(l|d)
        //val prev = docIdToCellDist.getOrElse(doc.getId, null)
        val cellDistGivenDocument =
          if (docIdToCellDist contains doc.getId)
            filterAndNormalize(docIdToCellDist(doc.getId), toponym)
          else
            null
        /*if(prev != null) {
          println("prev size = " + prev.size)
          println(" new size = " + cellDistGivenDocument.size)
          println("-----")
        }*/

        val topFreq = toponymsToFrequencies.getOrElse(toponym.getForm, 0.0)
        val lambda = topFreq / (topFreq + C)//0.7
        //println(toponym.getForm+" "+lambda)

        var indexToSelect = -1
        var maxProb = 0.0
        var candIndex = 0

        val totalPopulation = toponym.getCandidates.map(_.getPopulation).sum
        val maxPopulation = toponym.getCandidates.map(_.getPopulation).max

        var candDist = weightsForWMD.get(toponymLexicon.get(toponym.getForm))
        if(candDist == null) {
          candDist = new ArrayList[Double](toponym.getAmbiguity)
          for(i <- 0 until toponym.getAmbiguity) candDist.add(0.0)
        }

        //print("\n"+toponym.getForm+" ")

        for(cand <- toponym.getCandidates) {
          val candLoc = cand.getRegion.getCenter

          //print(" " + curCellNum)

          val localContextComponent =
          if(candDistGivenLocalContext != null)
            candDistGivenLocalContext.getOrElse(candIndex, 0.0)
          else
            0.0

          //print(localContextComponent + " ")

          /* If the document's cell dist exists, find the region containing
           * the candidate, if any, and fetch its probability. Else 0.0.
           */
          val documentComponent =
          if(cellDistGivenDocument != null && cellDistGivenDocument.size > 0) {
            cellDistGivenDocument.find { case (region, prob) =>
              region.contains(candLoc)
            }.map(_._2).getOrElse(0.0)
          } else
            0.0
          // errprint(s"Cand: $cand, documentComponent: $documentComponent")

          /*if(localContextComponent == 0.0) {
            if(documentComponent == 0.0) {
              println("BOTH ZERO")
            }
            else {
              println("LOCAL ZERO")
            }
          }
          else if(documentComponent == 0.0)
            println("DOC ZERO")*/

          // Incorporate administrative level here
          val adminLevelComponent = getAdminLevelComponent(cand, toponym.getCandidates.toList/*cand.getType, cand.getAdmin1Code*/)

          // P(l|t,d)
          val probComponent = adminLevelComponent * (lambda * localContextComponent + (1-lambda) * documentComponent)

          val probOfLocation =
          if(meProbOnly)
            localContextComponent
          else if(dgProbOnly)
            documentComponent
          else if(totalPopulation > 0) {
            val popComponent = cand.getPopulation.toDouble / totalPopulation
            popComponentCoefficient * popComponent + (1 - popComponentCoefficient) * probComponent
          }
          else probComponent
          /*if(totalPopulation > 0 && maxPopulation.toDouble / totalPopulation > .89)
            cand.getPopulation.toDouble / totalPopulation
          else probComponent*/

          candDist.set(candIndex, candDist.get(candIndex) + probOfLocation)

          //print(" " + probOfLocation)

          if(probOfLocation > maxProb) {
            indexToSelect = candIndex
            maxProb = probOfLocation
          }

          candIndex += 1
        }

        weightsForWMD.set(toponymLexicon.get(toponym.getForm), candDist)

        /*if(indexToSelect == -1) {
          val predDocLocation = predDocLocations.getOrElse(doc.getId, null)
          if(predDocLocation != null) {
            val indexToSelectBackoff = toponym.getCandidates.zipWithIndex.minBy(p => p._1.getRegion.distance(predDocLocation))._2
            if(indexToSelectBackoff != -1) {
              indexToSelect = indexToSelectBackoff
            }
          }
        }*/

        toponym.setSelectedIdx(indexToSelect)

      }
      tokIndex += 1
    }
  }

  if (weightDataPath != null) {
    val out = new DataOutputStream(new FileOutputStream(weightDataPath)) // "probToWMD.dat"
    for(weights <- weightsForWMD/*.filterNot(x => x == null)*/) {
      if(weights == null)
        out.writeInt(0)
      else {
        val sum = weights.sum
        out.writeInt(weights.size)
        for(i <- 0 until weights.size) {
          val newWeight = if(sum > 0) (weights.get(i) / sum) * weights.size else 1.0
          weights.set(i, newWeight)
          out.writeDouble(newWeight)
          //println(newWeight)
        }
        //println
      }
    }
    out.close
  }

  // Backoff to DocDist:
  val docDistResolver = new DocDistResolver(logFilePath)
  docDistResolver.overwriteSelecteds = false
  docDistResolver.disambiguate(corpus)
    
  corpus
  }

  def getAdminLevelComponent(loc:Location, candList:List[Location]): Double = {
    val numerator = loc.getRegion.getRepresentatives.size
    val denominator = candList.map(_.getRegion.getRepresentatives.size).sum
    val frac = numerator.toDouble / denominator
    frac
  }

  /**
   * Filter a distribution over cells to contain only the cells containing
   * one of the candidates of the given toponym, and renormalize the
   * probabilities appropriately.
   */
  def filterAndNormalize(dist:Map[RectRegion, Double], toponym:Toponym): Map[RectRegion, Double] = {
    val locs = toponym.getCandidates.map(l => l.getRegion.getCenter)
    val filteredDist = dist.filter { case (region, prob) =>
      locs.exists { l =>
        // errprint(s"region: $region, l: $l, contains: ${region.contains(l)}")
        region.contains(l)
      } }
    // errprint(s"dist size after filtering: ${filteredDist.size}")
    val sum = filteredDist.map(_._2).sum
    filteredDist.map(c => (c._1, c._2 / sum))
  }

  //val countryRE = """^\w\w\.\d\d$""".r
  val usStateRE = """^US\.[A-Za-z][A-Za-z]$""".r

  def getAdminLevelComponentOld(locType:Location.Type, admin1Code:String): Double = {
    if(locType == Location.Type.STATE) {
      if(usStateRE.findFirstIn(admin1Code) != None) { // US State
        .2
      }
      else { // Country
        .7
      }
    }
    else if(locType == Location.Type.CITY) { // City
      0.095
    }
    else { // Other
      0.005
    }
  }

}
