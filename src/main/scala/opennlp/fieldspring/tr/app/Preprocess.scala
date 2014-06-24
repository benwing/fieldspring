///////////////////////////////////////////////////////////////////////////////
//  Copyright (C) 2010 Travis Brown, The University of Texas at Austin
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
package opennlp.fieldspring
package tr.app

import java.io._

import tr.topo.gaz._
import tr.text._
import tr.text.io._
import tr.text.prep._
import tr.util.Constants
import util.io.localfh

object Preprocess extends App {
  override def main(args: Array[String]) {
    val divider = new OpenNLPSentenceDivider
    val tokenizer = new OpenNLPTokenizer
    val recognizer = new OpenNLPRecognizer
    val gazetteer = new InMemoryGazetteer

    gazetteer.load(new WorldReader(new File(
      Constants.getGazetteersDir() + File.separator + "dataen-fixed.txt.gz"
    )))

    val corpus = Corpus.createStreamCorpus

    val in = localfh.get_buffered_reader_handling_compression(args(0))
    corpus.addSource(
     new ToponymAnnotator(new PlainTextSource(in, divider, tokenizer, args(0)),
     recognizer, gazetteer
    ))

    val writer = new CorpusXMLWriter(corpus)
    writer.write(new File(args(1)))
  }
}

