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
package opennlp.fieldspring.tr.text;

import java.util.Iterator;

import opennlp.fieldspring.tr.util.Lexicon;
import opennlp.fieldspring.tr.app.*;
import java.io.*;

/**
 * A corpus consisting of documents, which in turn consist of sentences,
 * which in turn consist of tokens, which may be toponyms. There are two
 * types of corpora: stream corpora, where the documents can be iterated
 * through only once, and stored corpora, which do not have this restriction.
 * Stored corpora are implemented using `CompactCorpus`, which wraps
 * a stream corpus.
 */
public abstract class Corpus<A extends Token> implements Iterable<Document<A>>, Serializable {

  private static Enum<BaseApp.CORPUS_FORMAT> corpusFormat = null;//BaseApp.CORPUS_FORMAT.PLAIN;

  public abstract void addSource(DocumentSource source);
  public abstract void close();

  public static Corpus<Token> createStreamCorpus() {
    return new StreamCorpus();
  }

  public static StoredCorpus createStoredCorpus() {
    return new CompactCorpus(Corpus.createStreamCorpus());
  }

  public DocumentSource asSource() {
    final Iterator<Document<A>> iterator = this.iterator();

    return new DocumentSource() {
      public boolean hasNext() {
        return iterator.hasNext();
      }

      public Document<Token> next() {
        return (Document<Token>) iterator.next();
      }
    };
  }

  public Enum<BaseApp.CORPUS_FORMAT> getFormat() {
      return corpusFormat;
  }

  public void setFormat(Enum<BaseApp.CORPUS_FORMAT> corpusFormat) {
      Corpus.corpusFormat = corpusFormat;
  }
}

