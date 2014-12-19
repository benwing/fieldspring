package opennlp.fieldspring
package tr.app

import util.io.localfh
import tr.topo._
import tr.util._
import tr.topo.gaz._
import tr.text.io._
import tr.text.prep._

import org.clapper.argot._
import ArgotConverters._

import java.util.zip._
import java.io._

import scala.collection.JavaConversions._
import scala.collection.mutable

object ConvertCwarToGoldCorpus extends App {

  val parser = new ArgotParser("fieldspring run opennlp.fieldspring.tr.app.ConvertCwarToGoldCorpus", preUsage = Some("Fieldspring"))

  val corpusDirParam = parser.option[String](List("c", "corpus"), "corpus", "raw (XML) corpus dir")
  val kmlInputFileParam = parser.option[String](List("k", "kml"), "kml", "gold KML input file with coordinates")
  val tgnInputFileParam = parser.option[String](List("t", "tgn"), "tgn", "text file with direct TGN-to-coordinate mapping")
  val gazInputFileParam = parser.option[String](List("g", "gaz"), "gaz", "serialized gazetteer input file")

  val digitsRE = """^\d+$""".r
  val floatRE = """^-?\d*\.\d*$""".r
  val toponymWithSpacesRE = """tgn,(\d+)-(.*?)-]]""".r
  val toponymRE = """^tgn,(\d+)-(.+)-]][^\s]*$""".r

  val tokenizer = new OpenNLPTokenizer

  def errprint(str: String) {
    System.err.println(str)
  }

  try {
    parser.parse(args)
  }
  catch {
    case e: ArgotUsageException => errprint(e.message); sys.exit(1)
  }

  if (tgnInputFileParam == None && gazInputFileParam == None) {
    errprint("Either -t (--tgn) or -k (--kml), or both, need to be specified"); sys.exit(1)
  }
  val corpusFiles =
    new File(corpusDirParam.value.get).listFiles.sortBy(_.getName)
  val maybeGoldKml = kmlInputFileParam.value.map(scala.xml.XML.loadFile)
  val gazIn = gazInputFileParam.value.get

  val tgnToCoordKml =
    maybeGoldKml.map { goldKml =>
      //(goldKml \\ "Placemark").foreach { placemark =>
      (for(placemark <- (goldKml \\ "Placemark")) yield {
        var tgn = -1
        (placemark \\ "Data").foreach { data =>
          val name = (data \ "@name").text
          if(name.equals("tgn")) {
            val text = data.text.trim
            if(digitsRE.findFirstIn(text) != None)
              tgn = text.toInt
          }
        }
        var coordsRaw = ""
        (placemark \\ "coordinates").foreach { coordinates =>
          coordsRaw = coordinates.text.trim.dropRight(2)
        }

        val coordsSplit = coordsRaw.split(",")

        if(tgn == -1 || coordsSplit.size != 2 || floatRE.findFirstIn(coordsSplit(0)) == None
           || floatRE.findFirstIn(coordsSplit(1)) == None)
          None
        else {
          val lng = coordsSplit(0).toDouble
          val lat = coordsSplit(1).toDouble
          Some((tgn, Coordinate.fromDegrees(lat, lng)))
        }
      }).flatten.toMap
    }
  val tgnToCoordTgn =
    tgnInputFileParam.value.map { tgnInputFile =>
      (for {line <- localfh.openr(tgnInputFile)
            Array(tgncode, lat, long) = line.split(" ")
            tgn = tgncode.toInt
            coord = Coordinate.fromDegrees(lat.toDouble, long.toDouble)
           } yield (tgn -> coord)
      ).toMap
    }
  val tgnToCoord = (tgnToCoordKml ++ tgnToCoordTgn).reduce(_ ++ _)
  
  //tgnToCoord.foreach(println)


  var gaz:GeoNamesGazetteer = null;
  //println("Reading serialized GeoNames gazetteer from " + gazIn + " ...")
  var ois:ObjectInputStream = null;
  if(gazIn.toLowerCase().endsWith(".gz")) {
    val gis = new GZIPInputStream(new FileInputStream(gazIn))
    ois = new ObjectInputStream(gis)
  }
  else {
    val fis = new FileInputStream(gazIn)
    ois = new ObjectInputStream(fis)
  }
  gaz = ois.readObject.asInstanceOf[GeoNamesGazetteer]

  println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
  println("<corpus>")

  def escapeXML(form: String) = {
    // FIXME: This is kind of bogus. Instances of & will generally already
    // be entities, so we should preserve them. However, tokenization with
    // OpenNLP will generally separate off the semicolon, and stripPunc
    // will strip trailing semicolons and leading ampersands, so we'd need
    // some work to keep the entities intact.
    form.replaceAll("&", "&amp;").replaceAll("\"", "&quot;")
  }

  def processToken(token: String) {
    val strippedToken = escapeXML(TextUtil.stripPunc(token))
    if (strippedToken.length > 0
      // && CorpusXMLWriter.isSanitary(strippedToken)
    )
      println("      <w tok=\""+strippedToken+"\"/>")
  }

  def processText(form: String) {
    for(tok <- tokenizer.tokenize(form))
      processToken(tok)
  }

  def processToponym(form: String, tgnCode: Int) {
    val candidates = gaz.lookup(form.toLowerCase)
    val goldCoord = tgnToCoord.getOrElse(tgnCode, null)
    if(candidates == null || goldCoord == null) {
      // If doesn't match and toponym contains a comma, try to make a
      // toponym out of just the part before the comma. (FIXME: Perhaps
      // we should also try to make a toponym out of the rest, which will
      // normally be a state or country. Similar to ToponymWikiSource,
      // we could match on a toponym of type STATE whose location is within
      // 500 km or so of the gold location.)
      if (form contains ',') {
        val splitform = form.split(", *", 2)
        processToponym(splitform(0), tgnCode)
        processToken(",")
        processText(splitform(1))
      } else {
        processText(form)
      }
    }
    else {
      var matchingCand:Location = null
      var minDist = Double.MaxValue
      // Find minimum distance, ensure it's at most 50km
      for(cand <- candidates) {
        val dist = cand.getRegion.getCenter.distanceInKm(goldCoord)
        if (dist < minDist) {
          minDist = dist
          matchingCand = cand
        }
      }
      if (matchingCand == null) {
        processText(form)
      } else if (minDist > (
          if (matchingCand.getType == Location.Type.STATE) 500 else 50
        )) {
        //errprint(s"Rejecting closest candidate %s,%s for %s because distance %.2f km is too much" format (
        //  matchingCand.getName, matchingCand.getAdmin1Code, form, minDist))
        processText(form)
      }
      //val formToWrite = if(CorpusXMLWriter.isSanitary(form)) form else "MALFORMED"
      else { //if(CorpusXMLWriter.isSanitary(form))
        println("      <toponym term=\""+escapeXML(form)+"\">")
        println("        <candidates>")
        for(cand <- candidates) {
          val region = cand.getRegion
          val center = region.getCenter
          print("          <cand id=\""+cand.getId+"\" lat=\""+center.getLatDegrees+"\" long=\""
                +center.getLngDegrees+"\" type=\""+cand.getType+"\" admin1code=\""
                +cand.getAdmin1Code+"\"")
          if(matchingCand == cand)
            print(" selected=\"yes\"")
          println("/>")
          /*println("            <representatives>")
          for(rep <- region.getRepresentatives) {
            println("              <rep lat=\""+rep.getLatDegrees+"\" long=\""+rep.getLngDegrees+"\"/>")
          }
          println("            </representatives>")*/
        }
        println("        </candidates>")
        println("      </toponym>")
      }
    }
  }

  for(file <- corpusFiles) {
    println("  <doc id=\""+file.getName+"\">")

    for(line <- localfh.openr(file.toString).map(_.trim).filter(_.length > 0)) {
      val toponyms = mutable.Map[String, (String, Int)]()
      var nextToponym = 0
      println("    <s>")
      val fixedline = toponymWithSpacesRE.replaceAllIn(line, m => {
        val form = m.group(2).replaceAll("-", " ")
        val tgnCode = m.group(1).toInt
        val index = s"qtoponymq$nextToponym"
        nextToponym += 1
        toponyms(index) = (form, tgnCode)
        s" $index "
      })
      for (token <- tokenizer.tokenize(fixedline.replaceAll("-]]", ""))) {
        if (toponyms contains token) {
          val (form, tgnCode) = toponyms(token)
          processToponym(form, tgnCode)
        } else {
          processToken(token)
        }
      }
      println("    </s>")
    }
    
    println("  </doc>")
  }

  println("</corpus>")
}
