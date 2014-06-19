package opennlp.fieldspring
package tr.util

import util.io.localfh
import util.print.errprint
import tr.topo._

object LogUtil {

  val DPC = 1.0

  val CELL_BOTTOM_LEFT_COORD_PREFIX = "Cell ("
  val NGRAM_DIST_PREFIX = "unseen mass, "
  val ngramAndCountRE = """^(\S+)\=(\S+)$""".r

  val documentLineRE = """.*Document (.*) at \(?(\S+?),(\S+?)\)?:.*""".r
  val predictedCellLineRE = """.*  Predicted cell \(at rank ([0-9]+), kl-div (\S+?)\): [A-Za-z]+Cell\(#.*?, (\S+?),(\S+?):.*""".r
  val oldPredictedCellLineRE = """.*  Predicted cell \(at rank ([0-9]+), kl-div (\S+?)\): GeoCell\(\((\S+?),(\S+?)\).*""".r
  val neighborRE = """.*  #([0-9]+) close neighbor: \(?(\S+?),(\S+?)\)?;.*""".r
  val predictedCellCentralPointLineRE = """.* to predicted cell (?:central point|center) at \((\S+?),(\S+?)\).*""".r
  val averageDistanceRE = """.*  Average distance from .*""".r

  def parseLogFile(filename: String): List[LogFileParseElement]/*List[(String, Coordinate, Coordinate, List[(Coordinate, Int)])]*/ = {
    val lines = localfh.openr(filename)

    var docName:String = null
    var neighbors:List[(Coordinate, Int)] = null
    var predCells:List[(Int, Double, Coordinate)] = null
    var trueCoord:Coordinate = null
    var predCoord:Coordinate = null

    (for(line <- lines) yield {
      if(line.startsWith("#")) {

        line match {
          case documentLineRE(theDocName, lat, long) => {
            docName = theDocName
            if(docName.contains("/"))
              docName = docName.drop(docName.indexOf("/")+1)
            trueCoord = Coordinate.fromDegrees(lat.toDouble, long.toDouble)
            predCells = List()
            neighbors = List()
            None
          }
          case predictedCellLineRE(rank, kl, bllat, bllong) => {
            val blCoord = Coordinate.fromDegrees(bllat.toDouble, bllong.toDouble)
            predCells = predCells ::: ((rank.toInt, kl.toDouble, blCoord) :: Nil)
            None
          }
          case oldPredictedCellLineRE(rank, kl, bllat, bllong) => {
            val blCoord = Coordinate.fromDegrees(bllat.toDouble, bllong.toDouble)
            predCells = predCells ::: ((rank.toInt, kl.toDouble, blCoord) :: Nil)
            None
          }
          case neighborRE(rank, lat, long) => {
            val curNeighbor = Coordinate.fromDegrees(lat.toDouble, long.toDouble)
            neighbors = neighbors ::: ((curNeighbor, rank.toInt) :: Nil)
            None
          }
          case predictedCellCentralPointLineRE(lat, long) => {
            predCoord = Coordinate.fromDegrees(lat.toDouble, long.toDouble)
            None
          }
          case averageDistanceRE() => {
            assert(docName != null)
            assert(neighbors != null)
            assert(neighbors.size > 0)
            assert(predCells != null)
            assert(predCells.size > 0)
            assert(trueCoord != null)
            assert(predCoord != null)
            Some(new LogFileParseElement(docName, trueCoord, predCoord, predCells, neighbors))
          }
          case _ => {
            //errprint("Unparsed line: %s", line)
            None
          }
        }
      }
      else None
    }).flatten.toList
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
  val predCells: List[(Int, Double, Coordinate)],
  val neighbors: List[(Coordinate, Int)]) {

    def getProbDistOverPredCells(knn:Int, dpc:Double): List[(Int, Double)] = {
      var sum = 0.0
      val myKNN = if(knn < 0) predCells.size else knn
      (for((rank, kl, blCoord) <- predCells.take(myKNN)) yield {
        val unnormalized = math.exp(-kl)
        sum += unnormalized
        (TopoUtil.getCellNumber(blCoord, dpc), unnormalized)
      }).map(p => (p._1, p._2/sum)).toList
    }
}
