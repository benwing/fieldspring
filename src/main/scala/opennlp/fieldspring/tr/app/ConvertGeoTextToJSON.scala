package opennlp.fieldspring
package tr.app

import util.io.localfh

import net.liftweb.json._
import net.liftweb.json.JsonDSL._

object ConvertGeoTextToJSON extends App {
  for (line <- localfh.openr(args(0), encoding="ISO-8859-1")) {
    val tokens = line.split("\t")
    println(pretty(render(
      ("lat" -> tokens(3).toDouble) ~
      ("lon" -> tokens(4).toDouble) ~
      ("text" -> tokens(5)))))
  }
}

// case class tweet(val lat:Double, val lon:Double, val text:String)
