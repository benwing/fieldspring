///////////////////////////////////////////////////////////////////////////////
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
package opennlp.fieldspring.tr.text.io

import java.io._

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.util.control.Breaks._

import opennlp.fieldspring.tr.text._
import opennlp.fieldspring.tr.text.prep._
import opennlp.fieldspring.tr.topo.{Coordinate, Location}
import opennlp.fieldspring.tr.topo.gaz.Gazetteer
import opennlp.fieldspring.tr.util.Span

case class FileFormatException(msg: String) extends RuntimeException(msg)

/**
 * A TextSource that reads from Wikipedia articles output using
 * 'processwiki.py --generate-toponym-eval --raw-text --one-article-per-line'.
 *
 * A full command line call to processwiki.py might be
 *
 * bzcat enwiki-20131104-civil-war-permuted-pages-articles.xml.bz2 | \
 *   processwiki.py --generate-toponym-eval --coords-file ../enwiki-20131104/enwiki-20131104-permuted-coords.txt \
 *   --article-data-file ../enwiki-20131104/enwiki-20131104-permuted-document-info.txt \
 *   --raw-text --one-article-per-line
 */
class ToponymWikiSource(
  reader: BufferedReader, divider: SentenceDivider, tokenizer: Tokenizer,
  gazetteer: Gazetteer
) extends TextSource(reader) {

  val TITLE_PREFIX = "Article title: "
  val TITLE_INDEX = TITLE_PREFIX.length
  val ID_PREFIX = "Article ID: "
  val ID_INDEX = ID_PREFIX.length
  val MAX_ALLOWABLE_DISTANCE = 100
  val MAX_ALLOWABLE_STATE_DISTANCE = 500
  val toponym_regex = """\[\[\[(?:Link|Synthlink): (.*?)@@@(.*?)@@@(.*?)\]\]\]""".r

  val code_to_state = Map(
    "AL" -> "Alabama",
    "AK" -> "Alaska",
    "AZ" -> "Arizona",
    "AR" -> "Arkansas",
    "CA" -> "California",
    "CO" -> "Colorado",
    "CT" -> "Connecticut",
    "DC" -> "District of Columbia",
    "DE" -> "Delaware",
    "FL" -> "Florida",
    "GA" -> "Georgia",
    "HI" -> "Hawaii",
    "ID" -> "Idaho",
    "IL" -> "Illinois",
    "IN" -> "Indiana",
    "IA" -> "Iowa",
    "KS" -> "Kansas",
    "KY" -> "Kentucky",
    "LA" -> "Louisiana",
    "ME" -> "Maine",
    "MD" -> "Maryland",
    "MA" -> "Massachusetts",
    "MI" -> "Michigan",
    "MN" -> "Minnesota",
    "MS" -> "Mississippi",
    "MO" -> "Missouri",
    "MT" -> "Montana",
    "NE" -> "Nebraska",
    "NV" -> "Nevada",
    "NH" -> "New Hampshire",
    "NJ" -> "New Jersey",
    "NM" -> "New Mexico",
    "NY" -> "New York",
    "NC" -> "North Carolina",
    "ND" -> "North Dakota",
    "OH" -> "Ohio",
    "OK" -> "Oklahoma",
    "OR" -> "Oregon",
    "PA" -> "Pennsylvania",
    "PR" -> "Puerto Rico",
    "RI" -> "Rhode Island",
    "SC" -> "South Carolina",
    "SD" -> "South Dakota",
    "TN" -> "Tennessee",
    "TX" -> "Texas",
    "UT" -> "Utah",
    "VT" -> "Vermont",
    "VA" -> "Virginia",
    "VI" -> "Virgin Islands",
    "WA" -> "Washington",
    "WV" -> "West Virginia",
    "WI" -> "Wisconsin",
    "WY" -> "Wyoming"
  )
  val state_to_code = code_to_state.map { case (x,y) => (y,x) }

  def errprint(fmt: String, args: Any*) {
    System.err.println(fmt format (args:_*))
  }

  def debprint(fmt: String, args: Any*) {
    // System.err.println(fmt format (args:_*))
  }

  var current = readLine

  def hasNext: Boolean = current != null

  def next = {
    debprint("Called ToponymWikiSource.next")
    if (current == null || !current.startsWith(TITLE_PREFIX))
      throw new FileFormatException("Didn't find '%s', '%s'" format (TITLE_PREFIX, current))
    val title = current.drop(TITLE_INDEX).trim
    current = readLine
    if (current == null || !current.startsWith(ID_PREFIX))
      throw new FileFormatException("Didn't find '%s', '%s'" format (ID_PREFIX, current))
    val id = current.drop(ID_INDEX).trim
    current = readLine
    new Document[Token](id, title) {
      def iterator: java.util.Iterator[Sentence[Token]] = {
        debprint("Called ToponymWikiSource.Document.iterator")
        case class WikiToponym(anchortext: String, article: String,
          coord: Coordinate) { }
        val wiki_toponyms = mutable.Map[String, WikiToponym]()
        var next_wiki_toponym = 0
        val sentences = mutable.Buffer[Sentence[Token]]()
        if (current == null)
          throw new FileFormatException("Unexpected end of file")
        val doctext = current.trim
        current = readLine

        for (sentence <- divider.divide(doctext)) {
          debprint("Saw sentence: %s", sentence)
          // Snarf the link toponyms in the sentence and replace them
          // with an index of the form qtoponymq### where ### is a number,
          // in order to tokenize the sentence.
          val indexed_sentence = toponym_regex.replaceAllIn(sentence,
            m => {
              // processwiki.py replaced periods with underscores to avoid
              // confusing the OpenNLP sentence detector, so undo this
              val Array(lat, long) = m.group(3).replace('_', '.').split(",")
              val coord = Coordinate.fromDegrees(lat.toDouble, long.toDouble)
              val wikitop = WikiToponym(m.group(1), m.group(2), coord)
              val topindex = s"qtoponymq${next_wiki_toponym}"
              next_wiki_toponym += 1
              wiki_toponyms(topindex) = wikitop
              topindex
            })
          debprint("Indexed sentence: %s", indexed_sentence)
          val toks = mutable.Buffer[Token]()
          val toponym_spans = mutable.Buffer[Span[Token]]()

          // Tokenize the string and record its tokens
          def add_tokens(form: String) {
            for (form_token <- tokenizer.tokenize(form)) {
              debprint("Adding token in add_tokens: %s", form_token)
              toks += new SimpleToken(form_token)
            }
          }

          // Create and record a Toponym from a link toponym in the Wikipedia
          // source. FORM is the anchor text in the Wikipedia article,
          // ARTICLE the article linked to, and COORD the coordinate.
          def process_wiki_toponym(rawform: String, article: String,
              coord: Coordinate) {
            val form = rawform.trim
            if (form == "")
              return
            debprint("Called process_wiki_toponym")
            val span_start = toks.size
            // Add toponym's tokens
            add_tokens(form)
            // Look up toponym
            val candidates = gazetteer.lookup(form.toLowerCase)
            if (candidates != null) {
              // FIXME: Code copied from ToponymAnnotator. Why is this needed?
              for (loc <- candidates) {
                val reps = loc.getRegion.getRepresentatives
                val prevSize = reps.size
                Coordinate.removeNaNs(reps)
                if (reps.size < prevSize)
                  loc.getRegion.setCenter(Coordinate.centroid(reps))
              }
              // Find closest candidate and set as selected.
              val closest_indexed_candidate = candidates.zipWithIndex.minBy {
                case (loc, index) => loc.distance(coord)
              }
              val (closest_cand, closest_index) = closest_indexed_candidate
              // Reject closest candidate if too far from the Wikipedia location
              val closest_dist = closest_cand.distanceInKm(coord)
              if (closest_dist > (
                if (closest_cand.getType == Location.Type.STATE)
                  MAX_ALLOWABLE_STATE_DISTANCE
                else
                  MAX_ALLOWABLE_DISTANCE)
              ) {
                errprint("Rejecting closest candidate %s,%s for %s->%s because distance %.2f km is too much",
                  closest_cand.getName, closest_cand.getAdmin1Code, form,
                  article, closest_dist)
              } else {
                val toponym = new SimpleToponym(form, candidates, closest_index)
                debprint("Creating SimpleToponym for %s->%s with closest candidate %s,%s", form, article, closest_cand.getName, closest_cand.getAdmin1Code)
                debprint("Creating SimpleToponym(%s, %s, %s)", form, candidates,
                  closest_index)
                // Check that the toponym's state (if any) matches the
                // state of the closest candidate.
                if (article contains ", ") {
                  val wikitop_state = article.replaceAll(".*, ", "")
                  if (state_to_code contains wikitop_state) {
                    val cand_admin1 = closest_cand.getAdmin1Code
                    val cand_loc = "%s,%s" format (closest_cand.getName,
                      cand_admin1)
                    if (!cand_admin1.startsWith("US."))
                      errprint("For wiki toponym %s->%s, closest candidate %s not in US", form, article, cand_loc)
                    else {
                      val cand_state_code = cand_admin1.drop(3)
                      if (!(code_to_state contains cand_state_code))
                        errprint("For wiki toponym %s->%s, closest candidate %s doesn't have state code", form, article, cand_loc)
                      else if (code_to_state(cand_state_code) != wikitop_state)
                        errprint("For wiki toponym %s->%s, closest candidate %s doesn't match in state", form, article, cand_loc)
                    }
                  }
                }
                // Record a toponym span covering toponym's tokens.
                toponym_spans +=
                  new Span[Token](span_start, toks.size, toponym)
              }
            } else {
              errprint("Can't find %s -> %s (%s) in gazetteer",
                form, article, coord)
            }
          }

          // Tokenize the sentence and process each token.
          for (token <- tokenizer.tokenize(indexed_sentence)) {
            // See if we found an indexed token (e.g. qtoponymq2). If so,
            // fetch the corresponding link toponym, look up the candidates
            // in the gazetteer, and record a Toponym.
            if (wiki_toponyms contains token) {
              val wikitop = wiki_toponyms(token)
              val form = wikitop.anchortext
              // If the anchor text is of the form "CITY, STATE", record
              // a toponym for the city, and a separate one for the state.
              if (form contains ", ") {
                val city_state = "(.*), (.*)".r
                form match {
                  case city_state(city, state) => {
                    process_wiki_toponym(city, wikitop.article, wikitop.coord)
                    debprint("Adding token comma")
                    toks += new SimpleToken(",")
                    val span_start = toks.size
                    add_tokens(state)
                    // Look up candidates for state
                    val state_cands = gazetteer.lookup(state.toLowerCase)
                    if (state_cands == null)
                      errprint("Can't find state '%s' in gazetteer?", state)
                    else {
                      var found_state = false
                      breakable {
                        // Find the correct candidate by matching the
                        // admin code
                        for ((cand, index) <- state_cands.zipWithIndex) {
                          if (cand.getType == Location.Type.STATE) {
                            if (!state_to_code.contains(state))
                              errprint("Don't recognize state '%s'", state)
                            else if (cand.getAdmin1Code ==
                                "US." + state_to_code(state)) {
                              debprint("Found matching state in candidate with admin code %s", cand.getAdmin1Code)
                              val toponym = new SimpleToponym(state,
                                state_cands, index)
                              debprint("Creating SimpleToponym for state %s with candidate %s,%s", state, cand.getName, cand.getAdmin1Code)
                              debprint("Creating state SimpleToponym(%s, %s, %s)",
                                state, state_cands, index)
                              toponym_spans += new Span[Token](span_start,
                                toks.size, toponym)
                              found_state = true
                              break
                            } else
                              errprint("Found state candidate for '%s' but admin code '%s' doesn't match", state, cand.getAdmin1Code)
                          }
                        }
                      }
                      if (!found_state)
                        errprint("Can't find appropriate candidate for state '%s'", state)
                    }
                  }
                }
              } else
                process_wiki_toponym(form, wikitop.article, wikitop.coord)
            } else {
              debprint("Adding token in last else: %s", token)
              toks += new SimpleToken(token)
            }
          }
          // Record sentence
          sentences += new SimpleSentence[Token](null, toks, toponym_spans)
        }
        sentences.toIterator
      }
    }
  }
}
