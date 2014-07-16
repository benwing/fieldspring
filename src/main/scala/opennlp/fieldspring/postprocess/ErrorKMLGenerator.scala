///////////////////////////////////////////////////////////////////////////////
//  ErrorKMLGenerator.scala
//
//  Copyright (C) 2011, 2012 Mike Speriosu, The University of Texas at Austin
//  Copyright (C) 2014 Ben Wing, The University of Texas at Austin
//
//  Licensed under the Apache License, Version 2.0 (the "License");
//  you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.
///////////////////////////////////////////////////////////////////////////////

package opennlp.fieldspring.postprocess

import java.io._
import javax.xml.datatype._
import javax.xml.stream._
import opennlp.fieldspring.tr.topo._
import opennlp.fieldspring.tr.util.KMLUtil
import opennlp.fieldspring.tr.util.LogUtil
import scala.collection.JavaConversions._
import scala.collection.mutable
import org.clapper.argot._

/**
 * This takes a TextGrounder log file produced using
 * '--print-results-as-list --print-knn-results' and outputs a KML file showing
 * the predicted and gold-standard locations of documents and an arc between
 * them. '--log' is used to specify the log file to read and
 * '--kml' the KML file to output. Normally, the arc goes from the
 * gold-standard location (in yellow) to the predicted location (in blue),
 * but if '--pred' is given, it goes the other way.
 *
 * If '--noarc' is given, no arc is drawn nor is the pin at the end of the arc.
 * Instead, only the gold-standard location is shown, or if '--pred' is given,
 * the predicted location.
 *
 * If '--split' is given, documents are assumed to have a name like
 * 'agassiz.life.ie.txt.p27' indicating a particular paragraph in a
 * particular source book, and a separate KML file will be created for each
 * book. In such a case, the name given using '--kml' should be a prefix,
 * and the part minus the paragraph marker plus '.kml' will be appended,
 * e.g. if '--kml /foo/bar.' is given, then /foo/bar.agassiz.life.ie.txt.kml
 * will be created.
 */
object ErrorKMLGenerator {

  val factory = XMLOutputFactory.newInstance

  import ArgotConverters._

  val parser = new ArgotParser("fieldspring run opennlp.fieldspring.postprocess.ErrorKMLGenerator", preUsage = Some("Fieldspring"))
  val logFile = parser.option[String](List("l", "log"), "log", "log input file")
  val kmlOutFile = parser.option[String](List("k", "kml"), "kml", "kml output file")
  val usePred = parser.flag[Boolean](List("p", "pred"), "show predicted rather than gold locations")
  val noArc = parser.flag[Boolean](List("n", "noarc"), "don't draw arc between predicted and gold")
  val split = parser.flag[Boolean](List("s", "split"), "create separate kml files for each book")

  def main(args: Array[String]) {
    try {
      parser.parse(args)
    }
    catch {
      case e: ArgotUsageException => println(e.message); sys.exit(0)
    }

    if(logFile.value == None) {
      println("You must specify a log input file via -l.")
      sys.exit(0)
    }
    if(kmlOutFile.value == None) {
      println("You must specify a KML output file via -k.")
      sys.exit(0)
    }

    val rand = new scala.util.Random

    val kmlFiles = mutable.Map[String, XMLStreamWriter]()

    /* Split a document name into book and paragraph, if possible. Something
     * like 'agassiz.life.ie.txt.p27' will be split into
     * 'agassiz.life.ie.txt' and 'p27'; splitting is on the last period.
     */
    def splitDocName(docName: String) = {
      if (split.value == None) ("", docName)
      else {
        val splitRe = """^(.*)\.([^.]+)$""".r
        docName match {
          case splitRe(book, para) => (book, para)
          case _ => ("", docName)
        }
      }
    }

    /* Return KML file for `book`, creating one if necessary. */
    def getKMLFile(book: String) = {
      if (kmlFiles contains book)
        kmlFiles(book)
      else {
        val kmlfile1 = kmlOutFile.value.get
        val kmlfile =
          if (book == "") kmlfile1
          else s"$kmlfile1$book.kml"
        val outFile = new File(kmlfile)
        val stream = new BufferedOutputStream(new FileOutputStream(outFile))
        val out = factory.createXMLStreamWriter(stream, "UTF-8")
        val kmltitle = "errors-at-%s%s%s" format (
          if (usePred.value == None) "true" else "pred",
          if (book == "") "" else "-",
          book
        )
        KMLUtil.writeHeader(out, kmltitle)
        kmlFiles(book) = out
        out
      }
    }

    for(pe <- LogUtil.parseLogFile(logFile.value.get)) {
      val predCoord = Coordinate.fromDegrees(pe.predCoord.getLatDegrees() + (rand.nextDouble() - 0.5) * .1,
                                             pe.predCoord.getLngDegrees() + (rand.nextDouble() - 0.5) * .1)

      //val dist = trueCoord.distanceInKm(predCoord)

      val goldColor = "yellow"
      val predColor = "blue"
      val (coord1, color1) =
        if (usePred.value == None) (pe.trueCoord, goldColor)
        else (predCoord, predColor)
      val (coord2, color2) =
        if (usePred.value == None) (predCoord, predColor)
        else (pe.trueCoord, goldColor)

      val (book, docName) = splitDocName(pe.docName)
      val out = getKMLFile(book)
      if (noArc.value == None)
        KMLUtil.writeArcLinePlacemark(out, coord1, coord2)
      KMLUtil.writePinPlacemark(out, docName, coord1, color1)
      //KMLUtil.writePlacemark(out, pe.docName, coord1, KMLUtil.RADIUS)
      if (noArc.value == None) {
        KMLUtil.writePinPlacemark(out, docName, coord2, color2)
        //KMLUtil.writePolygon(out, pe.docName, coord, KMLUtil.SIDES, KMLUtil.RADIUS, math.log(dist) * KMLUtil.BARSCALE/2)
      }
    }

    // Write footer to all KML files and close them out
    for ((book, kmlfile) <- kmlFiles) {
      KMLUtil.writeFooter(kmlfile)
      kmlfile.close
    }
  }
}
