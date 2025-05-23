// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
/*
 * Author: Knut Omang
 */
#include "matchobjectTest.h"
#include "fakerewriter.h"

// Comment out cerr below to ignore unimplemented tests
#define NOTEST(name)                                                                                              \
    std::cerr << std::endl                                                                                        \
              << __FILE__ << ':' << __LINE__ << ": "                                                              \
              << "No test for method '" << (name) << "'" << std::endl;

/*************************************************************************
 *                      Test methods
 *
 * This section contains boolean methods for testing each public method
 * in the class being tested
 *************************************************************************/

/**
 * Test of the Term method.
 */
void MatchObjectTest::testTerm() {
    // Test that two equal keywords are matched properly:
    TestQuery q("NEAR/2(word,PHRASE(near,word))");

    const char* content = "This is a small text with word appearing near word";
    size_t      content_len = strlen(content);

    // Fetch a result descriptor:
    auto res = juniper::Analyse(*juniper::TestConfig, q._qhandle, content, content_len, 0);
    _test(static_cast<bool>(res));

    // Do the scanning manually. This calls accept several times
    res->Scan();
    Matcher& m = *res->_matcher;

    _test(m.TotalHits() == 3); // 3 occurrences
    match_candidate_set& ms = m.OrderedMatchSet();

    _test(ms.size() == 2);

    // printf("%d %d\n", m.TotalHits(),ms.size());
    TestQuery q1("t*t");
    TestQuery q2("*ea*");
    TestQuery q3("*d");
    TestQuery q4("*word");
    auto      r1 = juniper::Analyse(*juniper::TestConfig, q1._qhandle, content, content_len, 0);
    auto      r2 = juniper::Analyse(*juniper::TestConfig, q2._qhandle, content, content_len, 0);
    auto      r3 = juniper::Analyse(*juniper::TestConfig, q3._qhandle, content, content_len, 0);
    auto      r4 = juniper::Analyse(*juniper::TestConfig, q4._qhandle, content, content_len, 0);
    _test(static_cast<bool>(r1));
    if (r1) {
        r1->Scan();
        _test(r1->_matcher->TotalHits() == 1);
    }
    _test(static_cast<bool>(r2));
    if (r2) {
        r2->Scan();
        _test(r2->_matcher->TotalHits() == 2);
    }

    if (r3) {
        r3->Scan();
        _test(r3->_matcher->TotalHits() == 2);
    } else {
        _test(static_cast<bool>(r3));
    }

    if (r4) {
        r4->Scan();
        _test_equal(r4->_matcher->TotalHits(), 2);
    } else {
        _test(static_cast<bool>(r4));
    }
}

/**
 * Test of the Match method.
 */
void MatchObjectTest::testMatch() {
    // Check that we hit on the longest match first
    juniper::QueryParser p("AND(junipe,juniper)");
    juniper::QueryHandle qh(p, NULL);

    MatchObject*    mo = qh.MatchObj();
    juniper::Result res(*juniper::TestConfig, qh, "", 0);
    unsigned        opts = 0;
    match_iterator  mi(mo, &res);
    ucs4_t          ucs4_str[10];
    Fast_UnicodeUtil::ucs4copy(ucs4_str, "junipers");
    Token token;
    token.token = ucs4_str;
    token.curlen = 8;
    int idx = mo->Match(mi, token, opts);
    _test(strcmp(mo->Term(idx)->term(), "juniper") == 0);

    {
        // This test would loop in v.2.2.2
        TestQuery q("(word,");
        _test(q._qparser.ParseError());
    }

    {
        // Test to trigger ticket #5734 Dev Data Search
        std::string       doc("A simple document with an extremelylongwordhit in the middle of it that is"
                                    "long enough to allow the error to be triggered extremelylongwordhit."
                                    "A simple document with an extremelylongwordhit in the middle of it that is"
                                    "long enough to allow the error to be triggered extremelylongwordhit."
                                    "A simple document with an extremelylongwordhit in the middle of it that is"
                                    "long enough to allow the error to be triggered extremelylongwordhit."
                                    "A simple document with an extremelylongwordhit in the middle of it that is"
                                    "long enough to allow the error to be triggered extremelylongwordhit."
                                    "A simple document with an extremelylongwordhit in the middle of it that is"
                                    "long enough to allow the error to be triggered extremelylongwordhit."
                                    "A simple document with an extremelylongwordhit in the middle of it that is"
                                    "long enough to allow the error to be triggered extremelylongwordhit."
                                    "A simple document with an extremelylongwordhit in the middle of it that is"
                                    "long enough to allow the error to be triggered extremelylongwordhit.");
        TestQuery         q("OR(OR(extremelylongwordhits,extremelylongwordhit,extremelylongwordhits,"
                                    "extremelylongwordhit,extremelylongwordhits,extremelylongwordhit,"
                                    "extremelylongwordhit,extremelylongwordhits,extremelylongwordhit,"
                                    "extremelylongwordhit,extremelylongwordhits,extremelylongwordhit,"
                                    "extremelylongwordhit,extremelylongwordhits,extremelylongwordhit,"
                                    "extremelylongwordhit,extremelylongwordhits,extremelylongwordhit,"
                                    "extremelylongwordhit))");
        QueryHandle&      qh1(q._qhandle);
        juniper::Result   res1(*juniper::TestConfig, qh1, doc.c_str(), doc.size());
        juniper::Summary* sum = res1.GetTeaser(NULL);
        std::string       s(sum->Text());
        _test_equal(s, "A simple document with an <b>extremelylongwordhit</b> in the middle"
                       " of it that islong enough to allow...triggered "
                       "<b>extremelylongwordhit</b>.A simple document with an "
                       "<b>extremelylongwordhit</b> in the middle of it that islong enough to allow...");
    }
}

/**
 * Test matching in annotated buffers
 */
void MatchObjectTest::testMatchAnnotated() {
    const char*       doc = "A big and ugly teaser about "
                            "\xEF\xBF\xB9"
                            "buying"
                            "\xEF\xBF\xBA"
                            "buy"
                            "\xEF\xBF\xBB"
                            " stuff";
    TestQuery         q("AND(big,buy)");
    QueryHandle&      qh1(q._qhandle);
    juniper::Result   res1(*juniper::TestConfig, qh1, doc, strlen(doc));
    juniper::Summary* sum = res1.GetTeaser(NULL);
    std::string       s(sum->Text());

    _test_equal(s, "A <b>big</b> and ugly teaser about <b>"
                   "\xEF\xBF\xB9"
                   "buying"
                   "\xEF\xBF\xBA"
                   "buy"
                   "\xEF\xBF\xBB"
                   "</b> stuff");
}

/** Test parameter input via options
 */

void MatchObjectTest::testParams() {
    {
        TestQuery    q("AND(a,b)", "near.1");
        QueryHandle& qh = q._qhandle;
        std::string  stk;
        qh.MatchObj()->Query()->Dump(stk);
        // Expect l:1 == limit:1 v: Validity check of keywords needed, c: Completeness req'ed
        _test_equal(stk, "Node<a:2,l:1,v,c>[a:100,b:100]");
    }

    {
        TestQuery    q("AND(a,b)", "onear.1");
        QueryHandle& qh = q._qhandle;
        std::string  stk;
        qh.MatchObj()->Query()->Dump(stk);
        // Expect l:1 == limit:1 o: ordered, v: Validity check of keywords needed,
        //        c: Completeness req'ed
        _test_equal(stk, "Node<a:2,o,l:1,v,c>[a:100,b:100]");
    }

    {
        TestQuery    q("AND(a,b)", "within.1");
        QueryHandle& qh = q._qhandle;
        std::string  stk;
        qh.MatchObj()->Query()->Dump(stk);
        // Expect l:1 == limit:1 o: ordered, v: Validity check of keywords needed,
        //        c: Completeness req'ed
        _test_equal(stk, "Node<a:2,o,l:1,v,c>[a:100,b:100]");
    }

    {
        // Check ONEAR.
        TestQuery    q("ONEAR/1(a,b)");
        QueryHandle& qh = q._qhandle;
        std::string  stk;
        qh.MatchObj()->Query()->Dump(stk);
        // Expect l:1 == limit:1 o: ordered, v: Validity check of keywords needed,
        //        c: Completeness req'ed
        _test_equal(stk, "Node<a:2,o,l:1,v,c>[a:100,b:100]");
    }

    {
        // Check that ANY works.
        TestQuery    q("ANY(a,b)");
        QueryHandle& qh = q._qhandle;
        std::string  stk;
        qh.MatchObj()->Query()->Dump(stk);
        // Expect l:1 == limit:1 o: ordered, v: Validity check of keywords needed,
        //        c: Completeness req'ed
        _test_equal(stk, "Node<a:2>[a:100,b:100]");
    }
}

/*************************************************************************
 *                      Test administration methods
 *************************************************************************/

/**
 * Set up common stuff for all test methods.
 * This method is called immediately before each test method is called
 */
bool MatchObjectTest::setUp() {
    return true;
}

/**
 * Tear down common stuff for all test methods.
 * This method is called immediately after each test method is called
 */
void MatchObjectTest::tearDown() {}

/**
 * Build up a map with all test methods
 */
void MatchObjectTest::init() {
    test_methods_["testTerm"] = &MatchObjectTest::testTerm;
    test_methods_["testMatch"] = &MatchObjectTest::testMatch;
    test_methods_["testMatchAnnotated"] = &MatchObjectTest::testMatchAnnotated;
    test_methods_["testParams"] = &MatchObjectTest::testParams;
}

/*************************************************************************
 *                         main entry points
 *************************************************************************/

void MatchObjectTest::Run(MethodContainer::iterator& itr) {
    try {
        if (setUp()) {
            (this->*itr->second)();
            tearDown();
        }
    } catch (...) { _fail("Got unknown exception in test method " + itr->first); }
}

void MatchObjectTest::Run(const char* method) {
    MethodContainer::iterator pos(test_methods_.find(method));
    if (pos != test_methods_.end()) {
        Run(pos);
    } else {
        std::cerr << "ERROR: No test method named \"" << method << "\"" << std::endl;
        _fail("No such method");
    }
}

void MatchObjectTest::Run() {
    for (MethodContainer::iterator itr(test_methods_.begin()); itr != test_methods_.end(); ++itr) Run(itr);
}

/*
 * Parse runtime arguments before running.
 * If the -m METHOD parameter is given, run only that method
 */
void MatchObjectTest::Run(int argc, char* argv[]) {
    for (int i = 1; i < argc; ++i) {
        if (strcmp(argv[i], "-m") == 0 && argc > i + 1) {
            Run(argv[++i]);
            return;
        }
    }
    Run();
}
