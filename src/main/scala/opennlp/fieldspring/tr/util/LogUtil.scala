package opennlp.fieldspring
package tr.util

import scala.collection.mutable

import util.io.localfh
import util.metering._
import util.print.errprint
import tr.topo._

object LogUtil {

  val DPC = 1.0

  val CELL_BOTTOM_LEFT_COORD_PREFIX = "Cell ("
  val NGRAM_DIST_PREFIX = "unseen mass, "
  val ngramAndCountRE = """^(\S+)\=(\S+)$""".r

  // BE VERY CAREFUL modifying these! They are designed to work with both
  // the old and new log file formats, except for predictedCellLineRE vs.
  // oldPredictedCellLineRE. An example of the old log file format is
  // enwiki-20120211-cwardev-g1dpc-20spd-100-kldiv-pgt.log.bz2, whereas
  // enwiki-20131104-cwardev-g1dpc-20spd-100-nbayes-dirichlet.log.bz2 is an
  // example of the new log file format.
  val documentLineRE = """.*Document (.*) at \(?(\S+?),(\S+?)\)?[: ].*""".r
  val predictedCellLineRE = """.*  Predicted cell \(at rank ([0-9]+), (?:kl-div|neg-score) (\S+?)\): [A-Za-z]+Cell\(#.*?, (\S+?),(\S+?):(\S+?),(\S+?),.*""".r
  val oldPredictedCellLineRE = """.*  Predicted cell \(at rank ([0-9]+), (?:kl-div|neg-score) (\S+?)\): GeoCell\(\((\S+?),(\S+?)\)-\((\S+?),(\S+?)\).*""".r
  val neighborRE = """.*  #([0-9]+) close neighbor: \(?(\S+?),(\S+?)\)?;.*""".r
  val predictedCellCentralPointLineRE = """.* to predicted cell (?:central point|center) at \((\S+?),(\S+?)\).*""".r
  val averageDistanceRE = """.*  Average distance from .*""".r

  def parseLogFile(filename: String): List[LogFileParseElement]/*List[(String, Coordinate, Coordinate, List[(Coordinate, Int)])]*/ = {
    val lines = localfh.openr(filename)

    var docName:String = null
    val neighbors = mutable.ListBuffer[(Coordinate, Int)]()
    val predCells = mutable.ListBuffer[(Int, Double, Coordinate, Coordinate)]()
    var trueCoord:Coordinate = null
    var predCoord:Coordinate = null

    // (lines.mapMetered(new Meter("reading", "log file line")) { line =>
    lines.flatMap { line =>
      if(line.startsWith("#")) {

        line match {
          case documentLineRE(theDocName, lat, long) => {
            docName = theDocName
            if(docName.contains("/"))
              docName = docName.drop(docName.indexOf("/")+1)
            trueCoord = Coordinate.fromDegrees(lat.toDouble, long.toDouble)
            neighbors.clear()
            predCells.clear()
            None
          }
          case predictedCellLineRE(rank, kl, swlat, swlong, nelat, nelong) => {
            val swCoord = Coordinate.fromDegrees(swlat.toDouble, swlong.toDouble)
            val neCoord = Coordinate.fromDegrees(nelat.toDouble, nelong.toDouble)
            predCells += ((rank.toInt, kl.toDouble, swCoord, neCoord))
            None
          }
          case oldPredictedCellLineRE(rank, kl, swlat, swlong, nelat, nelong) => {
            val swCoord = Coordinate.fromDegrees(swlat.toDouble, swlong.toDouble)
            val neCoord = Coordinate.fromDegrees(nelat.toDouble, nelong.toDouble)
            predCells += ((rank.toInt, kl.toDouble, swCoord, neCoord))
            None
          }
          case neighborRE(rank, lat, long) => {
            val curNeighbor = Coordinate.fromDegrees(lat.toDouble, long.toDouble)
            neighbors += ((curNeighbor, rank.toInt))
            None
          }
          case predictedCellCentralPointLineRE(lat, long) => {
            predCoord = Coordinate.fromDegrees(lat.toDouble, long.toDouble)
            None
          }
          case averageDistanceRE() => {
            assert(docName != null)
            assert(neighbors.size > 0)
            assert(predCells.size > 0)
            assert(trueCoord != null)
            assert(predCoord != null)
            Some(new LogFileParseElement(docName, trueCoord, predCoord, predCells.toList, neighbors.toList))
          }
          case _ => {
            //errprint("Unparsed line: %s", line)
            None
          }
        }
      }
      else None
    }.toList
  }

  def getNgramDists(filename: String): Map[Int, Map[String, Double]] = {
    val lines = localfh.openr(filename)

    (for(line <- lines) yield {
      if(line.startsWith(CELL_BOTTOM_LEFT_COORD_PREFIX)) {
        val blCoordStartIndex = CELL_BOTTOM_LEFT_COORD_PREFIX.length
        val blCoordEndIndex = line.indexOf(")", blCoordStartIndex)
        val rawBlCoord = line.slice(blCoordStartIndex, blCoordEndIndex).split(",")
        val cellNum = TopoUtil.getCellNumber(rawBlCoord(0).toDouble, rawBlCoord(1).toDouble, DPC)

        val ngramDistRawStartIndex = line.indexOf(NGRAM_DIST_PREFIX, blCoordEndIndex) + NGRAM_DIST_PREFIX.length
        val ngramDistRawEndIndex = line.indexOf(")", ngramDistRawStartIndex)
        val dist =
        (for(token <- line.slice(ngramDistRawStartIndex, ngramDistRawEndIndex).split(" ")) yield {
          if(ngramAndCountRE.findFirstIn(token) != None) {
            val ngramAndCountRE(ngram, count) = token
            Some((ngram, count.toDouble))
          }
          else
            None
        }).flatten.toMap

        Some((cellNum, dist))
      }
      else
        None
    }).flatten.toMap
  }

}

class LogFileParseElement(
  val docName: String,
  val trueCoord: Coordinate,
  val predCoord: Coordinate,
  val predCells: List[(Int, Double, Coordinate, Coordinate)],
  val neighbors: List[(Coordinate, Int)]
) {

  /* FIXME!! Don't use this because it assumes a uniform grid and won't
   * work with a K-d grid. Use getProbDistOverPredCells() and rewrite
   * code to remove uniform-grid assumption. */
  def getUniformGridProbDistOverPredCells(knn:Int, dpc: Double): List[(Int, Double)] = {
    var sum = 0.0
    val myKNN = if(knn < 0) predCells.size else knn
    (for ((rank, kl, swCoord, neCoord) <- predCells.take(myKNN)) yield {
      val unnormalized = math.exp(-kl)
      sum += unnormalized
      (TopoUtil.getCellNumber(swCoord, dpc), unnormalized)
    }).map(p => (p._1, p._2/sum)).toList
  }

  def getProbDistOverPredCells(knn:Int): List[(RectRegion, Double)] = {
    var sum = 0.0
    val myKNN = if(knn < 0) predCells.size else knn
    (for ((rank, kl, swCoord, neCoord) <- predCells.take(myKNN)) yield {
      val unnormalized = math.exp(-kl)
      sum += unnormalized
      (RectRegion.fromCoordinates(swCoord, neCoord), unnormalized)
    }).map(p => (p._1, p._2/sum)).toList
  }
}
