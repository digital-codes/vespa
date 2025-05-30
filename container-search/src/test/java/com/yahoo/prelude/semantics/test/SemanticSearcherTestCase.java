// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.prelude.semantics.test;

import com.yahoo.component.chain.Chain;
import com.yahoo.prelude.query.WeightedSetItem;
import com.yahoo.search.Query;
import com.yahoo.prelude.query.NullItem;
import com.yahoo.search.Result;
import com.yahoo.search.Searcher;
import com.yahoo.search.searchchain.Execution;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests semantic searching
 *
 * @author bratseth
 */
public class SemanticSearcherTestCase extends RuleBaseAbstractTestCase {

    public SemanticSearcherTestCase() {
        super("rules.sr");
    }

    @Test
    void testSingleShopping() {
        assertSemantics("brand:sony",
                "sony");
        assertSemantics("brand:sony!150",
                "sony!150");
    }

    @Test
    void testCombinedShopping() {
        assertSemantics("AND brand:sony category:camera",
                "sony camera");
    }

    @Test
    void testPhrasedShopping() {
        assertSemantics("AND brand:sony category:\"digital camera\"",
                "sony digital camera");
    }

    @Test
    void testSimpleLocal() {
        assertSemantics("AND listing:restaurant place:geary",
                "restaurant in geary");
    }

    @Test
    void testLocal() {
        assertSemantics("AND listing:restaurant place:\"geary street san francisco\"",
                "restaurant in geary street san francisco");
    }

    @Test
    void testLiteralReplacing() {
        assertSemantics("AND lord of rings", "lotr");
        assertSemantics("AND foo1 lord of rings bar2", "foo1 lotr bar2");
        assertSemantics("WEAKAND(100) lord of rings", "lotr", 0, Query.Type.WEAKAND);
        assertSemantics("WEAKAND(100) foo1 lord of rings bar2", "foo1 lotr bar2", 0, Query.Type.WEAKAND);
    }

    @Test
    void testAddingAnd() {
        assertSemantics("AND bar foobar:bar",
                "bar");
    }

    @Test
    void testAddingRank() {
        assertSemantics("RANK word foobar:word",
                "word");
    }

    @Test
    void testFilterIsIgnored() {
        assertSemantics("RANK word |a |word |b foobar:word",
                "word&filter=a word b");
        assertSemantics("RANK a |word |b",
                "a&filter=word b");
    }

    @Test
    void testAddingNegative() {
        assertSemantics("+java -coffee",
                "java");
    }

    @Test
    void testAddingNegativePluralToSingular() {
        assertSemantics("+javas -coffee",
                "javas");
    }

    @Test
    void testCombined() {
        assertSemantics("AND bar listing:restaurant place:\"geary street san francisco\" foobar:bar",
                "bar restaurant in geary street san francisco");
    }

    @Test
    void testStopWord() {
        assertSemantics("strokes", "the strokes");
    }

    @Test
    void testStopWords1() {
        assertSemantics("strokes", "be the strokes");
    }

    @Test
    void testStopWords2() {
        assertSemantics("strokes", "the strokes be");
    }

    @Test
    void testDontRemoveEverything() {
        assertSemantics("the", "the the the");
    }

    @Test
    void testMoreStopWordRemoval() {
        assertSemantics("hamlet", "hamlet to be or not to be");
    }

    @Test
    void testTypeChange() {
        assertSemantics("RANK default:typechange doors", "typechange doors");
    }

    @Test
    void testTypeChangeWithSingularToPluralButNonReplaceWillNotSingularify() {
        assertSemantics("RANK default:typechange door", "typechange door");
    }

    @Test
    void testExplicitContext() {
        assertSemantics("AND from:paris to:texas", "paris to texas");
    }

    @Test
    void testOrProduction() {
        assertSemantics("OR something somethingelse", "something");
        // I did not expect this:
        assertSemantics("OR (AND foo1 something bar2) somethingelse", "foo1 something bar2");
        // Nor this; we should fix documentation to emphasize that "+>" adding terms
        // always happens at the root of the query:
        assertSemantics("OR (RANK (AND foo1 (OR foo2 something bar1) bar2) bar3) somethingelse",
                        "foo1 AND (foo2 OR something OR bar1) AND bar2 RANK bar3",
                        0, Query.Type.ADVANCED);
    }

    @Test
    void testDoubleOrProduction() {
        assertSemantics("OR more evenmore", "somethingmore");
        // Strange ordering:
        assertSemantics("OR more (AND foo1 bar2) evenmore", "foo1 somethingmore bar2");
    }

    // This test is order dependent. Fix it!!
    @Test
    void testWeightedSetItem() {
        Query q = new Query();
        WeightedSetItem weightedSet = new WeightedSetItem("fieldName");
        weightedSet.addToken("a", 1);
        weightedSet.addToken("b", 2);
        q.getModel().getQueryTree().setRoot(weightedSet);
        assertSemantics("WEIGHTEDSET fieldName{[1]:\"a\",[2]:\"b\"}", q);
    }

    @Test
    void testNullQuery() {
        Query query = new Query(""); // Causes a query containing a NullItem
        doSearch(searcher, query, 0, 10);
        assertEquals(NullItem.class, query.getModel().getQueryTree().getRoot().getClass()); // Still a NullItem
    }

    @Test
    void testPhraseReplacementCornerCase() {
        assertSemantics("brand:smashtogether", "\"smash together\"");
        assertSemantics("brand:smashtogether", "smash-together");
        assertSemantics("AND foo1 brand:smashtogether bar2", "foo1 \"smash together\" bar2");
        assertSemantics("AND brand:smashtogether \"foo1 bar2\"", "\"foo1 smash together bar2\"");
        assertSemantics("OR brand:smashtogether \"foo1 bar2\"", "\"foo1 smash together bar2\"", 0, Query.Type.ANY);
        // the difference in ordering here is because the parsed query already has a WEAKAND root (with 1 child):
        assertSemantics("WEAKAND(100) \"foo1 bar2\" brand:smashtogether", "\"foo1 smash together bar2\"", 0, Query.Type.WEAKAND);
    }

    @Test
    void testWandAddition() {
        assertSemantics("WEAKAND(100) confused perplex", "confused");
        // not what I expected, but similar to OrProduction above:
        assertSemantics("WEAKAND(100) (AND dazed confused disoriented) perplex", "dazed confused disoriented");
        assertSemantics("WEAKAND(100) (OR foo1 (AND dazed confused disoriented) bar2) perplex",
                        "foo1 OR (dazed AND confused AND disoriented) OR bar2",
                        0, Query.Type.ADVANCED);
    }

    @Test
    void testWandReplacement() {
        assertSemantics("WEAKAND(100) greatest", "goat");
        assertSemantics("WEAKAND(100) greatest dazed", "dazed goat");
        assertSemantics("WEAKAND(100) greatest (AND dazed disoriented)", "dazed goat disoriented");
        assertSemantics("WEAKAND(100) the greatest of all time", "thegoat");
        // Strange ordering again:
        assertSemantics("WEAKAND(100) the (AND dazed disoriented) greatest of all time", "dazed thegoat disoriented");
    }

    private Result doSearch(Searcher searcher, Query query, int offset, int hits) {
        query.setOffset(offset);
        query.setHits(hits);
        return createExecution(searcher).search(query);
    }

    private Execution createExecution(Searcher searcher) {
        return new Execution(chainedAsSearchChain(searcher), Execution.Context.createContextStub());
    }

    private Chain<Searcher> chainedAsSearchChain(Searcher topOfChain) {
        List<Searcher> searchers = new ArrayList<>();
        searchers.add(topOfChain);
        return new Chain<>(searchers);
    }

}
