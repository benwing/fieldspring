package opennlp.fieldspring.tr.app

import java.io._
import java.util.zip._

import opennlp.fieldspring.tr.util._
import opennlp.fieldspring.tr.topo._
import opennlp.fieldspring.tr.topo.gaz._
import opennlp.fieldspring.tr.text._
import opennlp.fieldspring.tr.text.prep._
import opennlp.fieldspring.tr.text.io._
import opennlp.fieldspring.util.io.localfh

import scala.collection.JavaConversions._

import org.apache.commons.compress.compressors.bzip2._
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

  for (line <- localfh.openr(wikiCorpusInputFile.value.get)) {
    ids += line.split("\t")(0)
  }

  val wikiTextCorpus = Corpus.createStreamCorpus

  val reader = localfh.open_buffered_reader(
    wikiTextInputFile.value.get)
  wikiTextCorpus.addSource(new WikiTextSource(reader))
  wikiTextCorpus.setFormat(BaseApp.CORPUS_FORMAT.WIKITEXT)

  for(doc <- wikiTextCorpus) {
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
