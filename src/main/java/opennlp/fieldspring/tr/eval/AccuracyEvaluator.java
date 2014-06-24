/*
 * This is a simple Evaluator that assumes gold named entities were used in preprocessing. For each gold disambiguated toponym, the model
 * either got that Location right or wrong, and a Report containing the accuracy figure on this task is returned.
 */

package opennlp.fieldspring.tr.eval;

import opennlp.fieldspring.tr.text.*;

public class AccuracyEvaluator<A extends Token> extends Evaluator<A> {

    public AccuracyEvaluator(Corpus<A> corpus) {
        super(corpus);
    }

    @Override
    public Report evaluate() {

        Report report = new Report();

        for(Document<A> doc : corpus) {
            for(Sentence<A> sent : doc) {
                for(Toponym toponym : sent.getToponyms()) {
                    if(toponym.hasGold()) {
                        if(toponym.getGoldIdx() == toponym.getSelectedIdx()) {
                            report.incrementTP();
                        }
                        else {
                            report.incrementInstanceCount();
                        }
                    }
                }
            }
        }

        return report;
    }

    @Override
    public Report evaluate(Corpus<A> pred, boolean useSelected) {
        return null;
    }
}
