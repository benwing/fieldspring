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

object FilterGeotaggedWiki extends App {
  val parser = new ArgotParser("fieldspring run opennlp.fieldspring.tr.app.FilterGeotaggedWiki", preUsage = Some("Fieldspring"))

  val wikiTextInputFile = parser.option[String](List("w", "wiki"), "wiki", "wiki text input file")
  val wikiCorpusInputFile = parser.option[String](List("c", "corpus"), "corpus", "wiki corpus input file")

  try {
    parser.parse(args)
  }
  catch {
    case e: ArgotUsageException => println(e.message); sys.exit(0)
  }

  val ids = new collection.mutable.HashSet[String]

  val task = new Meter("reading", "line")
  task.foreach(localfh.openr(wikiCorpusInputFile.value.get)) { line =>
    ids += line.split("\t")(0)
  }

  val wikiTextCorpus = Corpus.createStreamCorpus

  val reader = localfh.open_buffered_reader(
    wikiTextInputFile.value.get)
  wikiTextCorpus.addSource(new WikiTextSource(reader))
  wikiTextCorpus.setFormat(BaseApp.CORPUS_FORMAT.WIKITEXT)

  val task2 = new Meter("processing", "document")
  task2.foreach(wikiTextCorpus) { doc =>
    if(ids contains doc.getId) {
      println("Article title: " + doc.title)
      println("Article ID: " + doc.getId)
      for(sent <- doc) {
        for(token <- sent) {
          println(token.getOrigForm)
        }
      }
    }
    else {
      for(sent <- doc) { for(token <- sent) {} }
    }
  }
}
