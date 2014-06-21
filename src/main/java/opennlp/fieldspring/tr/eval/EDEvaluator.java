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
package opennlp.fieldspring.tr.eval;

import java.util.Iterator;

import opennlp.fieldspring.tr.text.Document;
import opennlp.fieldspring.tr.text.Corpus;
import opennlp.fieldspring.tr.text.Sentence;
import opennlp.fieldspring.tr.text.Token;

public class EDEvaluator<A extends Token> extends Evaluator<A> {
  public EDEvaluator(Corpus<A> corpus) {
    super(corpus);
  }

  public Report evaluate() {
    return null;
  }

  public Report evaluate(Corpus<A> pred, boolean useSelected) {
    Iterator<Document<A>> goldDocs = this.corpus.iterator();
    Iterator<Document<A>> predDocs = pred.iterator();

    while (goldDocs.hasNext() && predDocs.hasNext()) {
      Iterator<Sentence<A>> goldSents = goldDocs.next().iterator();
      Iterator<Sentence<A>> predSents = predDocs.next().iterator();

      while (goldSents.hasNext() && predSents.hasNext()) {
      }

      assert !goldSents.hasNext() && !predSents.hasNext() : "Documents have different numbers of sentences.";
    }

    assert !goldDocs.hasNext() && !predDocs.hasNext() : "Corpora have different numbers of documents.";
    return null;
  }
}

