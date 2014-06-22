package opennlp.fieldspring
package tr.app

import util.io.localfh
import util.metering._
import util.print.errprint

import tr.util._

import org.clapper.argot._
import ArgotConverters._

/**
 * Combine a Wikipedia corpus data file with predictions from a run of
 * TextGrounder with --print-results --print-results-as-list --print-knn-results.
 */
object CombineTextGrounderPredictions extends App {
  val parser = new ArgotParser("fieldspring run opennlp.fieldspring.tr.app.CombineTextGrounderPredictions", preUsage = Some("Fieldspring"))

  val logFile = parser.option[String](List("l", "log"), "log", "TextGrounder log file run on corpus")
  val wikiCorpusInputFile = parser.option[String](List("c", "corpus"), "corpus", "wiki corpus input file")

  try {
    parser.parse(args)
  }
  catch {
    case e: ArgotUsageException => println(e.message); sys.exit(0)
  }

  val predDocLocations =
    (for (pe <- LogUtil.parseLogFile(logFile.value.get)) yield {
      (pe.docName, pe.predCoord)
    }).toMap

  val ids = new collection.mutable.HashSet[String]

  val task = new Meter("processing", "line")
  /* FIXME! This assumes a particular format for the fields.
   * We should use the TextDB API instead to allow for varied formats.
   */
  task.foreach(localfh.openr(wikiCorpusInputFile.value.get)) { line =>
    val splitline = line.split("\t", -1)
    if (splitline.length != 10)
      errprint("Bad line, expected 10 fields: %s", line)
    else {
      val Array(id, title, coord, incoming_links, redir, namespace, is_list_of,
        is_disambig, is_list, unigram_counts) = splitline
      if (predDocLocations.contains(title))
        println("%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s" format (id, title,
          predDocLocations(title), incoming_links, redir, namespace, is_list_of,
          is_disambig, is_list, unigram_counts))
      else
        errprint("Unable to locate location for document %s", title)
    }
  }
}
