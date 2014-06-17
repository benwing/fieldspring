package opennlp.fieldspring
package tr.util

import util.io.localfh

object StopwordUtil {

  def populateStoplist(filename: String): Set[String] = {
    var stoplist:Set[String] = Set()
    localfh.openr(filename).foreach(line => stoplist += line)
    stoplist.toSet()
    stoplist
  }

}
