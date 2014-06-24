package opennlp.fieldspring
package tr.app

import util.metering._

import tr.util._
import tr.topo._
import tr.topo.gaz._
import tr.text._
import tr.text.prep._
import tr.text.io._

import scala.collection.JavaConversions._

object ConvertCorpusToArticleText extends BaseApp {

  def main(args:Array[String]) { 

    initializeOptionsFromCommandLine(args)

    val corpus = TopoUtil.readStoredCorpusFromSerialized(getSerializedCorpusInputPath)
    
    var i = 0
    (new Meter("processing", "document")).foreach(corpus) { doc =>
      println("Article title: " + doc.getId)
      println("Article ID: " + i)
      for(sent <- doc) {
        for(rawToken <- sent) {
          // Split on spaces but also on a comma before a space or finally,
          // including the comma as a separate word.
          // FIXME: By the time we get here, most commas and periods have
          // been removed entirely. Perhaps we should remove the
          // remaining commas, too, by splitting on """,? |,$""".
          for(token <- rawToken.getOrigForm.split(""" |(?=, )|(?=,$)""")
              if token.length > 0) {
            println(token)
          }
        }
      }
      i += 1
    }
  }
}
