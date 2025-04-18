// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "auxTest.h"
#include "matchobjectTest.h"
#include "mcandTest.h"
#include "queryparserTest.h"
#include "suite.h"
#include "testenv.h"
#include <vespa/vespalib/testkit/test_kit.h>
/**
 * The SrcTestSuite class runs all the unit tests for the src module.
 *
 * @author Knut Omang
 */
class SrcTestSuite : public Suite {

public:
    SrcTestSuite();
};

SrcTestSuite::SrcTestSuite() : Suite("SrcTestSuite", &std::cout) {
    // All tests for this module
    AddTest(new MatchCandidateTest());
    AddTest(new MatchObjectTest());
    AddTest(new QueryParserTest());
    AddTest(new AuxTest());
}

int main(int argc, char** argv) {
    juniper::TestEnv te(argc, argv, TEST_PATH("./testclient.rc").c_str());
    SrcTestSuite     suite;
    suite.Run();
    long failures = suite.Report();
    suite.Free();
    return (int)failures;
}
