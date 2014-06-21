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
import java.util.List;

import opennlp.fieldspring.tr.text.Corpus;
import opennlp.fieldspring.tr.text.Document;
import opennlp.fieldspring.tr.text.Sentence;
import opennlp.fieldspring.tr.text.Token;
import opennlp.fieldspring.tr.text.Toponym;
import opennlp.fieldspring.tr.topo.Location;
import opennlp.fieldspring.tr.topo.Region;

public class SharedNEEvaluator<A extends Token> extends Evaluator<A> {
  /* The given corpus should include either gold or selected candidates or
   * both. */
  public SharedNEEvaluator(Corpus<A> corpus) {
    super(corpus);
  }

  /* Evaluate the "selected" candidates in the corpus using its "gold"
   * candidates. */
  public Report evaluate() {
    return this.evaluate(this.corpus, false);
  }

  /* Evaluate the given corpus using either the gold or selected candidates in
   * the current corpus. */
  public Report evaluate(Corpus<A> pred, boolean useSelected) {
    Report report = new Report();

    Iterator<Document<A>> goldDocs = this.corpus.iterator();
    Iterator<Document<A>> predDocs = pred.iterator();

    /* Iterate over documents in sync. */
    while (goldDocs.hasNext() && predDocs.hasNext()) {
      Iterator<Sentence<A>> goldSents = goldDocs.next().iterator();
      Iterator<Sentence<A>> predSents = predDocs.next().iterator();
      
      /* Iterate over sentences in sync. */
      while (goldSents.hasNext() && predSents.hasNext()) {
        List<Toponym> goldToponyms = goldSents.next().getToponyms();
        List<Toponym> predToponyms = predSents.next().getToponyms();

        /* Confirm that we have the same number of toponyms and loop through
         * them. */
        assert goldToponyms.size() == predToponyms.size() : "Named entity spans do not match!";
        for (int i = 0; i < goldToponyms.size(); i++) {
          Toponym predToponym = predToponyms.get(i);
          if (predToponym.hasSelected()) {
            Region predRegion = predToponym.getSelected().getRegion();
            List<Location> candidates = goldToponyms.get(i).getCandidates();

            double minDist = Double.POSITIVE_INFINITY;
            int minIdx = -1;
            for (int j = 0; j < candidates.size(); j++) {
              double dist = predRegion.distance(candidates.get(j).getRegion().getCenter());
              if (dist < minDist) {
                minDist = dist;
                minIdx = j;
              }
            }
            /*System.out.format("Size: %d, minDist: %f, minIdx: %d, goldIdx: %d\n",
              candidates.size(), minDist, minIdx, goldToponyms.get(i).getGoldIdx());*/

            if (minIdx == goldToponyms.get(i).getGoldIdx()) {
              report.incrementTP();
            } else {
              report.incrementInstanceCount();
            }
          } else {
            report.incrementInstanceCount();
          }
        }
      }
    }

    return report;
  }

  /* A convenience method providing a default for evaluate. */
  public Report evaluate(Corpus<A> pred) {
    return this.evaluate(pred, false);
  }
}

